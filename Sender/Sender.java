import java.io.*;
import java.net.*;
import java.util.*;

/**
 * DS-FTP Sender
 * Supports Stop-and-Wait (RDT 3.0) and Go-Back-N (GBN) protocols.
 *
 * Usage:
 *   java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]
 *
 *   Omit window_size → Stop-and-Wait
 *   Provide window_size → Go-Back-N
 */
public class Sender {

    public static void main(String[] args) throws Exception {

        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        String rcvIp = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMs = Integer.parseInt(args[4]);
        boolean isGBN = (args.length == 6);
        int windowSize = isGBN ? Integer.parseInt(args[5]) : 1;

        if (isGBN) {
            if (windowSize % 4 != 0 || windowSize > 128 || windowSize < 4) {
                System.err.println("Window size must be a multiple of 4 and <= 128.");
                System.exit(1);
            }
            System.out.println("[Sender] Mode: Go-Back-N | Window: " + windowSize);
        } 
        else {
            System.out.println("[Sender] Mode: Stop-and-Wait");
        }

        InetAddress rcvAddr = InetAddress.getByName(rcvIp);

        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeoutMs);

        byte[] fileData = readFile(inputFile);
        System.out.println("[Sender] File size: " + fileData.length + " bytes");

        // ---- Handshake ----
        long startTime = System.currentTimeMillis();
        doHandshake(socket, rcvAddr, rcvDataPort, timeoutMs);

        // ---- Transfer ----
        if (isGBN) {
            sendGBN(socket, rcvAddr, rcvDataPort, fileData, windowSize, timeoutMs);
        } 
        else {
            sendStopAndWait(socket, rcvAddr, rcvDataPort, fileData, timeoutMs);
        }

        // ---- Teardown ----
        long endTime = System.currentTimeMillis();
        double elapsed = (endTime - startTime) / 1000.0;
        System.out.printf("Total Transmission Time: %.2f seconds%n", elapsed);

        socket.close();
    }

    // -------------------------------------------------------------------------
    // Handshake: Send SOT (Seq=0), wait for ACK 0
    // -------------------------------------------------------------------------

    private static void doHandshake(DatagramSocket socket, InetAddress rcvAddr, int rcvDataPort, int timeoutMs) throws Exception {
        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        int timeoutCount = 0;

        while (true) {
            sendPacket(socket, sot, rcvAddr, rcvDataPort);
            System.out.println("[Sender] Sent SOT (Seq=0)");

            try {
                DSPacket ack = receivePacket(socket);
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[Sender] Handshake complete (ACK 0 received)");
                    return;
                }
            } 
            
            catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[Sender] Timeout waiting for SOT ACK (" + timeoutCount + "/3)");
                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    System.exit(1);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stop-and-Wait Transfer
    // -------------------------------------------------------------------------

    private static void sendStopAndWait(DatagramSocket socket, InetAddress rcvAddr, int rcvDataPort, byte[] fileData, int timeoutMs) throws Exception {
        int seq = 1;  // First DATA packet uses Seq=1
        int offset = 0;
        int totalBytes = fileData.length;

        // Handle empty file
        if (totalBytes == 0) {
            sendEOT(socket, rcvAddr, rcvDataPort, 1);
            return;
        }

        while (offset < totalBytes) {
            int chunkSize = Math.min(DSPacket.MAX_PAYLOAD_SIZE, totalBytes - offset);
            byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + chunkSize);
            DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, chunk);

            int timeoutCount = 0;
            boolean acked = false;

            while (!acked) {
                sendPacket(socket, dataPkt, rcvAddr, rcvDataPort);
                System.out.println("[Sender][S&W] Sent DATA Seq=" + seq + " (" + chunkSize + " bytes)");

                try {
                    DSPacket ack = receivePacket(socket);
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == seq) {
                        System.out.println("[Sender][S&W] ACK " + seq + " received");
                        timeoutCount = 0;
                        acked = true;
                    } 
                    else {
                        System.out.println("[Sender][S&W] Wrong ACK (got " + ack.getSeqNum() + ", expected " + seq + "), retransmitting...");
                    }
                } 
                
                catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[Sender][S&W] Timeout for Seq=" + seq + " (" + timeoutCount + "/3)");
                    if (timeoutCount >= 3) {
                        System.out.println("Unable to transfer file.");
                        System.exit(1);
                    }
                }
            }

            seq = (seq + 1) % 128;
            offset += chunkSize;
        }

        // Teardown
        sendEOT(socket, rcvAddr, rcvDataPort, seq);
    }

    // -------------------------------------------------------------------------
    // Go-Back-N Transfer
    // -------------------------------------------------------------------------

    private static void sendGBN(DatagramSocket socket, InetAddress rcvAddr, int rcvDataPort, byte[] fileData, int N, int timeoutMs) throws Exception {

        int totalBytes = fileData.length;

        // Handle empty file
        if (totalBytes == 0) {
            sendEOT(socket, rcvAddr, rcvDataPort, 1);
            return;
        }

        // Pre-build all DATA packets
        List<DSPacket> allPackets = buildDataPackets(fileData);
        int totalPackets = allPackets.size();

        int base = 0;  // index into allPackets (not seq num)
        int nextIdx = 0;  // next packet index to send
        int timeoutCount = 0;


        System.out.println("[Sender][GBN] Total packets to send: " + totalPackets);

        while (base < totalPackets) {

            while (nextIdx < base + N && nextIdx < totalPackets) {
                // Collect next group of 4 (or fewer at end)
                int groupEnd = Math.min(nextIdx + 4, totalPackets);
                
                List<DSPacket> group = new ArrayList<>();
                for (int i = nextIdx; i < groupEnd; i++) {
                    group.add(allPackets.get(i));
                }
                List<DSPacket> toSend = ChaosEngine.permutePackets(group);
                for (DSPacket pkt : toSend) {
                    sendPacket(socket, pkt, rcvAddr, rcvDataPort);
                    System.out.println("[Sender][GBN] Sent DATA Seq=" + pkt.getSeqNum());
                }
                nextIdx = groupEnd;
            }

            // --- Wait for ACKs ---
            try {
                DSPacket ack = receivePacket(socket);
                if (ack.getType() == DSPacket.TYPE_ACK) {
                    int ackedSeq = ack.getSeqNum();
                    int newBase = findNewBase(allPackets, base, ackedSeq);
                    if (newBase > base) {
                        System.out.println("[Sender][GBN] ACK " + ackedSeq + " → advancing base from " + base + " to " + newBase);
                        base = newBase;
                        timeoutCount = 0; // reset on progress
                    } 
                    else {
                        System.out.println("[Sender][GBN] Duplicate/old ACK " + ackedSeq + ", ignoring");
                    }
                }
            } 
            
            catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[Sender][GBN] Timeout (base Seq=" + allPackets.get(base).getSeqNum() + ") " + timeoutCount + "/3");
                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    System.exit(1);
                }
                // Retransmit entire window from base
                System.out.println("[Sender][GBN] Retransmitting window from base=" + base);
                nextIdx = base; // reset nextIdx so window refills from base
            }
        }

        // EOT sequence = (last DATA seq + 1) % 128
        int lastSeq = allPackets.get(totalPackets - 1).getSeqNum();
        int eotSeq  = (lastSeq + 1) % 128;
        sendEOT(socket, rcvAddr, rcvDataPort, eotSeq);
    }

    // -------------------------------------------------------------------------
    // EOT with retransmit
    // -------------------------------------------------------------------------

    private static void sendEOT(DatagramSocket socket, InetAddress rcvAddr, int rcvDataPort, int eotSeq) throws Exception {
        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
        int timeoutCount = 0;

        while (true) {
            sendPacket(socket, eot, rcvAddr, rcvDataPort);
            System.out.println("[Sender] Sent EOT (Seq=" + eotSeq + ")");

            try {
                DSPacket ack = receivePacket(socket);
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                    System.out.println("[Sender] EOT ACK received. Transfer complete.");
                    return;
                }
            } 
            
            catch (SocketTimeoutException e) {
                timeoutCount++;
                System.out.println("[Sender] Timeout waiting for EOT ACK (" + timeoutCount + "/3)");
                if (timeoutCount >= 3) {
                    System.out.println("Unable to transfer file.");
                    System.exit(1);
                }
            }
        }
    }

    /** Build all DATA packets from file bytes, seqs starting at 1 */
    private static List<DSPacket> buildDataPackets(byte[] fileData) {
        List<DSPacket> packets = new ArrayList<>();
        int seq = 1;
        int offset = 0;
        while (offset < fileData.length) {
            int chunkSize = Math.min(DSPacket.MAX_PAYLOAD_SIZE, fileData.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + chunkSize);
            packets.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunk));
            seq = (seq + 1) % 128;
            offset += chunkSize;
        }
        return packets;
    }

    /**
     * Given a cumulative ACK seq number, find the new base index.
     * The ACK confirms delivery of all packets up to and including ackedSeq.
     */
    private static int findNewBase(List<DSPacket> allPackets, int currentBase, int ackedSeq) {
        int newBase = currentBase;
        for (int i = currentBase; i < allPackets.size(); i++) {
            if (allPackets.get(i).getSeqNum() == ackedSeq) {
                newBase = i + 1;
                break;
            }
            // Handle wrap-around: if we pass the acked seq going forward, stop
        }
        return newBase;
    }

    /** Read entire file into a byte array */
    private static byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            fis.read(data);
        }
        return data;
    }

    /** Send a DSPacket to the given destination */
    private static void sendPacket(DatagramSocket socket, DSPacket pkt, InetAddress addr, int port) throws IOException {
        byte[] data = pkt.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, addr, port);
        socket.send(dp);
    }

    /** Receive and parse a DSPacket */
    private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new DSPacket(dp.getData());
    }
}