import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;
import okhttp3.*;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class Main {
  private static final Gson gson = new Gson();
  private static final Bencode bencode = new Bencode(true);

  public static void main(String[] args) throws Exception {
    String command = args[0];
    if ("decode".equals(command)) {
      String bencodedValue = args[1];
      Object decoded;
      try {
        final Bencode bencodeSimple = new Bencode();
        decoded = bencodeSimple.decode(bencodedValue.getBytes(), bencodeSimple.type(bencodedValue.getBytes()));
      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(gson.toJson(decoded));

    } else if ("info".equals(command)) {
      final byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

      final MetaInfo metaInfo = convertToMetaInfoObject(bencode.decode(torrentFile, Type.DICTIONARY));

      System.out.printf("Tracker URL: %s\n", metaInfo.announce);
      System.out.printf("Length: %s\n", metaInfo.info.length);
      System.out.printf("Info Hash: %s\n", asHex(getInfoHash(metaInfo.info)));
      System.out.printf("Piece Length: %s\n", metaInfo.info.pieceLength);
      System.out.println("Piece Hashes:");
      List<String> pieceHashes = getPieceHashes(metaInfo.info.pieces);
      pieceHashes.forEach(System.out::println);


    } else if ("peers".equals(command)) {
      final byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

      final MetaInfo metaInfo = convertToMetaInfoObject(bencode.decode(torrentFile, Type.DICTIONARY));

      final List<Peer> peers = getPeersFromTracker(metaInfo);
      System.out.println("Peers:");
      peers.forEach(System.out::println);

    } else if ("handshake".equals(command)) {
      final byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

      final MetaInfo metaInfo = convertToMetaInfoObject(bencode.decode(torrentFile, Type.DICTIONARY));

      final String[] peerAddr = args[2].split(":");

      final Socket socket = Networks.createConnection(peerAddr[0], Integer.parseInt(peerAddr[1]));
      Networks.performHandshake(socket, metaInfo);

      socket.close();

    } else if ("download_piece".equals(command)) {
      // download_piece -o /tmp/test-piece-0 sample.torrent 0
      final String outputPath = args[2];
      final int pieceId = Integer.parseInt(args[4]);
      final byte[] torrentFile = Files.readAllBytes(Paths.get(args[3]));

      final MetaInfo metaInfo = convertToMetaInfoObject(bencode.decode(torrentFile, Type.DICTIONARY));

      final List<Peer> peers = getPeersFromTracker(metaInfo);

      final Socket socket = Networks.createConnection(peers.get(0).ip.getHostAddress(), peers.get(0).port);

      Networks.preDownload(socket, metaInfo);

      final List<String> pieceHashes = getPieceHashes(metaInfo.info.pieces);

      final Networks networks = new Networks();
      final int pieceLength = pieceLength(pieceId, pieceHashes, metaInfo);
      final byte[] piece = networks.downloadPiece(pieceId, pieceLength, socket, pieceHashes);
//    pieces := getPieces(metaInfo)
//    piece := downloadPiece(pieceId, int(metaInfo.Info.PieceLength), connections[peer], pieces)
//    err = os.WriteFile(os.Args[3], piece, os.ModePerm)

      Files.write(new File(outputPath).toPath(), piece);

      System.out.printf("Piece %s downloaded to %s\n", pieceId, outputPath);

      socket.close();

    } else {
      System.out.println("Unknown command: " + command);
    }

  }

  static int pieceLength(int pieceIndex, List<String> pieces, MetaInfo metaInfo) {
    if (pieceIndex != pieces.size() - 1) {
      return (int) metaInfo.info.pieceLength;
    } else { // last piece
      long lastPieceSize = metaInfo.info.length - (metaInfo.info.pieceLength * pieceIndex);
      System.out.printf("Last Piece Size [%d - (%d*%d) = %d]\n", metaInfo.info.length, metaInfo.info.pieceLength, pieceIndex, lastPieceSize);
      return (int) lastPieceSize;
    }
  }

  static List<Peer> getPeersFromTracker(MetaInfo metaInfo) throws IOException {
    final byte[] infoHash = getInfoHash(metaInfo.info);
    final String urlEncodedInfoHash = URLEncoder.encode(new String(infoHash, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1.toString());

    HttpUrl.Builder urlBuilder =
        HttpUrl.parse(metaInfo.announce).newBuilder()
            .addEncodedQueryParameter("info_hash", urlEncodedInfoHash)
            .addQueryParameter("peer_id", "00112233445566778899")
            .addQueryParameter("port", "6881")
            .addQueryParameter("uploaded", "0")
            .addQueryParameter("downloaded", "0")
            .addQueryParameter("left", String.valueOf(metaInfo.info.length))
            .addQueryParameter("compact", "1");

    String url = urlBuilder.build().toString();

    System.out.println("url = " + url);

    Request request = new Request.Builder().get().url(url).build();

    OkHttpClient client = new OkHttpClient();

    Call call = client.newCall(request);
    Response response = call.execute();

    final byte[] responseBytes = Objects.requireNonNull(response.body()).bytes();
    final Map<String, Object> trackerResponseMap = bencode.decode(responseBytes, Type.DICTIONARY);

    final TrackerResponse trackerResponse = convertToTrackerResponse(trackerResponseMap);

    final List<Peer> peers = extractPeers(trackerResponse);
    return peers;
  }


  private static List<Peer> extractPeers(TrackerResponse trackerResponse) throws UnknownHostException {
    List<Peer> peers = new ArrayList<>();
    final int numPeers = trackerResponse.peers.remaining() / 6;

    for (int i = 0; i < numPeers; i++) {
      final byte[] IP = new byte[4];
      final byte[] PORT = new byte[2];
      trackerResponse.peers.get(IP);
      trackerResponse.peers.get(PORT);

      InetAddress ip = InetAddress.getByAddress(IP);
      int port = Short.toUnsignedInt(ByteBuffer.wrap(PORT).order(ByteOrder.BIG_ENDIAN).getShort());

      peers.add(new Peer(ip, port));
    }
    return peers;
  }

  private static TrackerResponse convertToTrackerResponse(Map<String, Object> trackerResponseMap) {
    final TrackerResponse trackerResponse = new TrackerResponse();
    trackerResponse.interval = (long) trackerResponseMap.get("interval");
    trackerResponse.peers = (ByteBuffer) trackerResponseMap.get("peers");
    return trackerResponse;
  }

  private static MetaInfo convertToMetaInfoObject(Map<String, Object> torrentFileDecoded) {
    final Map<String, Object> infoMap = (Map<String, Object>) torrentFileDecoded.get("info");


    final Info info = new Info();
    info.length = (long) infoMap.get("length");
    info.name = new String(((ByteBuffer) infoMap.get("name")).array());
    info.pieceLength = (long) infoMap.get("piece length");
    info.pieces = (ByteBuffer) infoMap.get("pieces");

    final MetaInfo metaInfo = new MetaInfo();
    metaInfo.announce = new String(((ByteBuffer) torrentFileDecoded.get("announce")).array());
    metaInfo.info = info;
    return metaInfo;
  }

  public static List<String> getPieceHashes(ByteBuffer pieces) {
    List<String> pieceHashes = new ArrayList<>();

    byte[] byteArray = new byte[20];
    while (pieces.remaining() >= 20) {
      pieces.get(byteArray);
      pieceHashes.add(asHex(byteArray.clone()));
    }
    if (pieces.remaining() != 0) {
      throw new RuntimeException("not read all piece hashes");
    }

    return pieceHashes;
  }

  static byte[] getInfoHash(Info info) {
    final HashMap<String, Object> infoMap = new HashMap<>();

    infoMap.put("length", info.length);
    infoMap.put("name", info.name);
    infoMap.put("piece length", info.pieceLength);
    infoMap.put("pieces", info.pieces);

    final byte[] encodedInfo = bencode.encode(infoMap);

    try {
      return MessageDigest.getInstance("SHA-1").digest(encodedInfo);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  static String asHex(byte[] digest) {
    StringBuilder hexString = new StringBuilder();

    for (byte b : digest) {
      hexString.append(String.format("%02x", b));
    }

    return hexString.toString();
  }

}

class MetaInfo {
  String announce;
  Info info;
}
class Info {
  long length;
  String name;
  long pieceLength;
  ByteBuffer pieces;
}
class TrackerResponse {
  long interval;
  ByteBuffer peers;
}
class Peer {
  InetAddress ip;
  int port;

  public Peer(InetAddress ip, int port) {
    this.ip = ip;
    this.port = port;
  }

  @Override
  public String toString() {
    return String.format("%s:%s", ip.getHostAddress(), port);
  }
}

