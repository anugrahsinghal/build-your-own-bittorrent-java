import javax.net.SocketFactory;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

public class Networks {

  public static final byte REQUEST = 6;
  public static final byte PIECE = 7;
  public static final byte BIT_FIELD = 5;
  public static final byte UNCHOKE = 1;
  public static final byte INTERESTED = 2;

  public static final int BLOCK_SIZE = 16 * 1024;

  static byte[] createPeerMessage(int messageId, byte[] payload) {
//    byte[] messsage = new byte[4+1+payload.length];
    final int messageLength = 1 + payload.length;
    final ByteBuffer peerMessageBuffer = ByteBuffer.allocate(4 + 1 + payload.length);
    peerMessageBuffer.putInt(messageLength);
    peerMessageBuffer.put((byte) messageId);
    peerMessageBuffer.put(payload);

    return peerMessageBuffer.array();
  }

  static byte[] waitFor(Socket socket, byte expectedMessageId) throws IOException {
    System.out.println("expectedMessageId = " + expectedMessageId);

    while (true) {
      byte[] messageLengthPrefix = new byte[4];
      readFully(socket.getInputStream(), messageLengthPrefix);

      int messageLength = ByteBuffer.wrap(messageLengthPrefix).order(ByteOrder.BIG_ENDIAN).getInt();
      System.out.println("messageLength = " + messageLength);

      byte[] receivedMessageId = new byte[1];
      readFully(socket.getInputStream(), receivedMessageId);

      final byte messageId = ByteBuffer.wrap(receivedMessageId).order(ByteOrder.BIG_ENDIAN).get();
      System.out.println("messageId = " + messageId);

      byte[] payload = new byte[messageLength - 1];
      readFully(socket.getInputStream(), payload);

      if (messageId == expectedMessageId) {
        return payload;
      } else {
        System.out.println("Repeat loop");
      }
    }
  }

  static void readFully(InputStream in, byte[] b) throws IOException {
    int bytesRead = 0;
    while (bytesRead < b.length) {
      int result = in.read(b, bytesRead, b.length - bytesRead);
      if (result == -1) {
        throw new EOFException();
      }
      bytesRead += result;
    }
  }

  static void preDownload(Socket socket, MetaInfo metaInfo) throws IOException {
    performHandshake(socket, metaInfo);

    Networks.waitFor(socket, Networks.BIT_FIELD);

    socket.getOutputStream().write(Networks.createPeerMessage(Networks.INTERESTED, new byte[0]));// peer message

    Networks.waitFor(socket, Networks.UNCHOKE);
  }

  static byte[] performHandshake(Socket socket, MetaInfo metaInfo) throws IOException {
    final int handshakeMessageSize = 1 + 19 + 8 + 20 + 20;


    final ByteBuffer payloadBuffer = ByteBuffer.allocate(handshakeMessageSize);

    payloadBuffer
        .put((byte) 19)
        .put("BitTorrent protocol".getBytes())
        .put(new byte[8])
        .put(Main.getInfoHash(metaInfo.info))
        .put("00112233445566778899".getBytes());

    socket.getOutputStream().write(payloadBuffer.array());

    final byte[] handshakeResponse = new byte[handshakeMessageSize];
    socket.getInputStream().read(handshakeResponse);

    final byte[] peerIdResponse = new byte[20];
    final ByteBuffer wrap = ByteBuffer.wrap(handshakeResponse);
    wrap.position(48);
    wrap.get(peerIdResponse, 0, 20);

    System.out.println("Peer ID: " + Main.asHex(peerIdResponse));

    return handshakeResponse;
  }

  static Socket createConnection(String ip, int port) {
    try {
      Socket socket = SocketFactory.getDefault().createSocket();
      socket.connect(new InetSocketAddress(ip, port));
      return socket;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  static byte[] downloadPiece(int pieceId, int pieceLength, Socket socket, List<String> pieceHashes) throws IOException {
    //	//fmt.Printf("PieceHash for id: %d --> %x\n", pieceId, pieces[pieceId])
    //	// say 256 KB
    //	// for each block
    sendRequestForPiece(pieceId, pieceLength, socket);

    System.out.printf("For Piece : [%d] of possible Size :[%d] Sent Requests for Blocks of size %d\n", pieceId, pieceLength, BLOCK_SIZE);

    byte[] combinedBlockToPiece = downloadRequestedPiece(pieceId, pieceLength, socket);

    boolean ok = verifyPiece(combinedBlockToPiece, pieceHashes, pieceId);
    if (!ok) {
      throw new RuntimeException("unequal pieces");
    }

    return combinedBlockToPiece;
  }

  static void sendRequestForPiece(int pieceId, int pieceLength, Socket socket) throws IOException {
    int blockCount = calculateBlockCount(pieceLength);

    for (int i = 0; i < blockCount; i++) {
      int begin = i * BLOCK_SIZE;
      int blockSize = BLOCK_SIZE;
      if ((pieceLength - begin) < BLOCK_SIZE) {
        blockSize = pieceLength - begin;
      }

      final ByteBuffer payload = ByteBuffer.allocate(12);
      payload.order(ByteOrder.BIG_ENDIAN).putInt(pieceId);
      payload.order(ByteOrder.BIG_ENDIAN).putInt(begin);
      payload.order(ByteOrder.BIG_ENDIAN).putInt(blockSize);

      final byte[] requestPayload = payload.array();

      final byte[] peerMessage = createPeerMessage(REQUEST, requestPayload);
      System.out.printf("begin = %d -- size = [%d] -- peerMessage = %s%n", begin, blockSize, Arrays.toString(peerMessage));

      socket.getOutputStream().write(peerMessage);
    }
  }

  static int calculateBlockCount(int pieceLength) {
    int carry = 0;
    if (pieceLength % BLOCK_SIZE > 0) {
      carry = 1;
    }
    int count = pieceLength / BLOCK_SIZE + carry;
    return count;
  }

  static boolean verifyPiece(byte[] combinedBlockToPiece, List<String> pieces, int pieceId) {
    try {
      String checksumStr = Main.asHex(MessageDigest.getInstance("SHA-1").digest(combinedBlockToPiece));
      return checksumStr.equals(pieces.get(pieceId));
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  static byte[] downloadRequestedPiece(int pieceId, int pieceLength, Socket socket) throws IOException {
    int blockCount = calculateBlockCount(pieceLength);

    byte[] combinedBlockToPiece = new byte[pieceLength];

    for (int i = 0; i < blockCount; i++) {
      byte[] data = waitFor(socket, PIECE);// PIECE

      ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

      int index = buffer.getInt();
      if (index != pieceId) {
        throw new RuntimeException(String.format("something went wrong [expected: %d -- actual: %d]", pieceId, index));
      }
      int begin = buffer.getInt();
      byte[] block = new byte[data.length - 8];
      buffer.get(block);
      System.arraycopy(block, 0, combinedBlockToPiece, begin, block.length);
    }
    return combinedBlockToPiece;
  }


}
