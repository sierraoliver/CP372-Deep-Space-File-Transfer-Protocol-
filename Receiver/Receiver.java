import java.io.*;
import java.net.*;
import java.util.*;

/**
 * DS-FTP Receiver
 * Supports Stop-and-Wait (RDT 3.0) and Go-Back-N (GBN) protocols.
 *
 * Usage:
 *   java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
 *
 *   RN = 0  → no ACKs dropped
 *   RN = X  → every Xth ACK is dropped (via ChaosEngine)
 *
 */
public class Receiver {

    public static void main(String[] args) throws Exception {

        if (args.length != 5) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }

        String senderIp = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        InetAddress senderAddr = InetAddress.getByName(senderIp);

        System.out.println("[Receiver] Listening on port " + rcvDataPort);
        System.out.println("[Receiver] Will send ACKs to " + senderIp + ":" + senderAckPort);
        System.out.println("[Receiver] Reliability Number (RN) = " + rn);

        DatagramSocket socket = new DatagramSocket(rcvDataPort);

        // ACK counter for ChaosEngine (1-indexed, counts ALL intended ACKs)
        int ackCount = 0;

        System.out.println("[Receiver] Waiting for SOT...");
        while (true) {
            DSPacket pkt = receivePacket(socket);
            if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
                System.out.println("[Receiver] SOT received (Seq=0)");
                ackCount++;
                sendACK(socket, senderAddr, senderAckPort, 0, ackCount, rn);
                break;
            }
        }

        // expectedSeq starts at 1 (first DATA packet)
        int expectedSeq = 1;
        int lastACKed = 0;  // tracks the last cumulative ACK sent (starts at SOT seq)

        // Buffer for out-of-order packets (GBN): seq → DSPacket
        Map<Integer, DSPacket> buffer = new LinkedHashMap<>();

        FileOutputStream fos = new FileOutputStream(outputFile);

        boolean done = false;

        while (!done) {
            DSPacket pkt = receivePacket(socket);
            byte type = pkt.getType();
            int seq = pkt.getSeqNum();

            if (type == DSPacket.TYPE_DATA) {

                System.out.println("[Receiver] Received DATA Seq=" + seq + " (expected=" + expectedSeq + ")");

                if (seq == expectedSeq) {
                    fos.write(pkt.getPayload());
                    lastACKed = seq;
                    expectedSeq = (expectedSeq + 1) % 128;

                    while (buffer.containsKey(expectedSeq)) {
                        DSPacket buffered = buffer.remove(expectedSeq);
                        fos.write(buffered.getPayload());
                        System.out.println("[Receiver] Delivered buffered Seq=" + expectedSeq);
                        lastACKed = expectedSeq;
                        expectedSeq = (expectedSeq + 1) % 128;
                    }

                    // Send cumulative ACK for last delivered in-order packet
                    ackCount++;
                    System.out.println("[Receiver] Sending cumulative ACK=" + lastACKed);
                    sendACK(socket, senderAddr, senderAckPort, lastACKed, ackCount, rn);

                } 
                else {
                    
                    if (!buffer.containsKey(seq)) {
                        buffer.put(seq, pkt);
                        System.out.println("[Receiver] Buffered out-of-order Seq=" + seq);
                    } 
                    else {
                        System.out.println("[Receiver] Duplicate buffered Seq=" + seq + ", discarding");
                    }

                    ackCount++;
                    System.out.println("[Receiver] Re-sending cumulative ACK=" + lastACKed);
                    sendACK(socket, senderAddr, senderAckPort, lastACKed, ackCount, rn);
                }

            } 
            else if (type == DSPacket.TYPE_EOT) {

                System.out.println("[Receiver] EOT received (Seq=" + seq + ")");
                ackCount++;
                sendACK(socket, senderAddr, senderAckPort, seq, ackCount, rn);
                done = true;

            } 
            else if (type == DSPacket.TYPE_SOT) {
                // Duplicate SOT (sender retransmitting handshake) — re-ACK
                System.out.println("[Receiver] Duplicate SOT received, re-sending ACK 0");
                ackCount++;
                sendACK(socket, senderAddr, senderAckPort, 0, ackCount, rn);
            }
        }

        fos.flush();
        fos.close();
        socket.close();
        System.out.println("[Receiver] File saved to: " + outputFile);
        System.out.println("[Receiver] Done. Exiting.");
    }

    /**
     * Send an ACK, subject to ChaosEngine drop simulation.
     *
     * ackCount - Number of ACKs INTENDED so far (1-indexed, including this one)
     * rn - Reliability Number
     */
    private static void sendACK(DatagramSocket socket, InetAddress addr, int port, int seq, int ackCount, int rn) throws IOException {
        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[Receiver] *** ACK " + seq + " DROPPED (ChaosEngine, count=" + ackCount + ") ***");
            return; 
        }

        byte[] data = ack.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, addr, port);
        socket.send(dp);
        System.out.println("[Receiver] Sent ACK Seq=" + seq);
    }

    /** Receive and parse a DSPacket from the socket */
    private static DSPacket receivePacket(DatagramSocket socket) throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new DSPacket(dp.getData());
    }
}