import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;
import lombok.AllArgsConstructor;
import lombok.Getter;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {
  private static final Gson GSON = new Gson();
  private static final Bencode BENCODE = new Bencode(true);

  public static void main(String[] args) throws Exception {
    String command = args[0];
    if ("decode".equals(command)) {
      String bencodedValue = args[1];
      Object decoded;
      try {
        Bencode bencodeSimple = new Bencode();
        decoded = bencodeSimple.decode(bencodedValue.getBytes(), bencodeSimple.type(bencodedValue.getBytes()));
      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(GSON.toJson(decoded));

      return;
    }
    switch (command) {

      case "info": {
        byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

        MetaInfo metaInfo = convertToMetaInfo(BENCODE.decode(torrentFile, Type.DICTIONARY));

        System.out.printf("Tracker URL: %s\n", metaInfo.announce);
        System.out.printf("Length: %s\n", metaInfo.info.length);
        System.out.printf("Info Hash: %s\n", asHex(getInfoHash(metaInfo.info)));
        System.out.printf("Piece Length: %s\n", metaInfo.info.pieceLength);
        System.out.println("Piece Hashes:");
        List<String> pieceHashes = getPieceHashes(metaInfo.info.pieces);
        pieceHashes.forEach(System.out::println);


        break;
      }
      case "peers": {
        byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

        MetaInfo metaInfo = convertToMetaInfo(BENCODE.decode(torrentFile, Type.DICTIONARY));

        List<Peer> peers = getPeersFromTracker(metaInfo);
        System.out.println("Peers:");
        peers.forEach(System.out::println);

        break;
      }
      case "handshake": {
        byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

        MetaInfo metaInfo = convertToMetaInfo(BENCODE.decode(torrentFile, Type.DICTIONARY));

        String[] peerAddr = args[2].split(":");

        Socket socket = Networks.createConnection(peerAddr[0], Integer.parseInt(peerAddr[1]));
        Networks.performHandshake(socket, metaInfo);

        socket.close();

        break;
      }
      case "download_piece": {
        //./your_bittorrent.sh download_piece -o /tmp/test-piece-0 sample.torrent 0
        String outputPath = args[2];
        byte[] torrentFile = Files.readAllBytes(Paths.get(args[3]));

        MetaInfo metaInfo = convertToMetaInfo(BENCODE.decode(torrentFile, Type.DICTIONARY));

        List<Peer> peers = getPeersFromTracker(metaInfo);

        Socket socket = Networks.createConnection(peers.get(0).ip.getHostAddress(), peers.get(0).port);

        Networks.preDownload(socket, metaInfo);

        List<String> pieceHashes = getPieceHashes(metaInfo.info.pieces);

        int pieceId = Integer.parseInt(args[4]);
        int pieceLength = pieceLength(pieceId, pieceHashes, metaInfo);
        byte[] piece = Networks.downloadPiece(pieceId, pieceLength, socket, pieceHashes);

        Files.write(new File(outputPath).toPath(), piece);

        System.out.printf("Piece %s downloaded to %s\n", pieceId, outputPath);

        socket.close();

        break;
      }
      case "download": {

        //./your_bittorrent.sh download -o /tmp/test.txt sample.torrent
        String outputPath = args[2];
        byte[] torrentFile = Files.readAllBytes(Paths.get(args[3]));

        MetaInfo metaInfo = convertToMetaInfo(BENCODE.decode(torrentFile, Type.DICTIONARY));

        List<Peer> peers = getPeersFromTracker(metaInfo);

        ConnectionPool connectionPool = ConnectionPool.create(peers);
        System.out.println("Connection Pool Created");

        List<String> pieceHashes = getPieceHashes(metaInfo.info.pieces);
        System.err.println("Number of tasks = " + pieceHashes.size());

        List<DownloadTask> downloadTasks = IntStream.range(0, pieceHashes.size())
            .mapToObj(pieceId -> new DownloadTask(pieceId, pieceLength(pieceId, pieceHashes, metaInfo), pieceHashes,
                metaInfo, connectionPool)
            ).collect(Collectors.toList());

        ExecutorService executorService = Executors.newFixedThreadPool(4);
        List<Future<PieceByte>> futures = executorService.invokeAll(downloadTasks);

        List<PieceByte> results = new ArrayList<>();

        for (Future<PieceByte> future : futures) {
          results.add(future.get());
        }
        results.sort(Comparator.comparing(PieceByte::getPieceId));

        ByteBuffer allBytes = ByteBuffer.allocate((int) metaInfo.info.length);

        results.forEach(pieceByte -> allBytes.put(pieceByte.data));

        Files.write(new File(outputPath).toPath(), allBytes.array());

        System.out.printf("Downloaded %s to %s.%n", args[3], outputPath);

        executorService.shutdownNow();

        connectionPool.closeConnections();

        break;
      }

      default:
        System.out.println("Unknown command: " + command);
        break;
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
    byte[] infoHash = getInfoHash(metaInfo.info);
    String urlEncodedInfoHash = URLEncoder.encode(new String(infoHash, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1.toString());

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

    byte[] responseBytes = Objects.requireNonNull(response.body()).bytes();
    Map<String, Object> trackerResponseMap = BENCODE.decode(responseBytes, Type.DICTIONARY);

    TrackerResponse trackerResponse = new TrackerResponse(
        (long) trackerResponseMap.get("interval"),
        (ByteBuffer) trackerResponseMap.get("peers")
    );

    List<Peer> peers = extractPeers(trackerResponse);
    return peers;
  }

  static List<Peer> extractPeers(TrackerResponse trackerResponse) throws UnknownHostException {
    List<Peer> peers = new ArrayList<>();
    int numPeers = trackerResponse.peers.remaining() / 6;

    for (int i = 0; i < numPeers; i++) {
      byte[] IP = new byte[4];
      byte[] PORT = new byte[2];
      trackerResponse.peers.get(IP);
      trackerResponse.peers.get(PORT);

      InetAddress ip = InetAddress.getByAddress(IP);
      int port = Short.toUnsignedInt(ByteBuffer.wrap(PORT).order(ByteOrder.BIG_ENDIAN).getShort());

      peers.add(new Peer(ip, port));
    }
    return peers;
  }

  static MetaInfo convertToMetaInfo(Map<String, Object> torrentFileDecoded) {
    Map<String, Object> infoMap = (Map<String, Object>) torrentFileDecoded.get("info");


    return new MetaInfo(
        bufferToString(torrentFileDecoded.get("announce")),
        new Info(
            (long) infoMap.get("length"),
            bufferToString(infoMap.get("name")),
            (long) infoMap.get("piece length"),
            (ByteBuffer) infoMap.get("pieces")
        )
    );
  }

  static String bufferToString(Object byteBuffer) {
    return new String(((ByteBuffer) byteBuffer).array());
  }

  static List<String> getPieceHashes(ByteBuffer pieces) {
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
    HashMap<String, Object> infoMap = new HashMap<>();

    infoMap.put("length", info.length);
    infoMap.put("name", info.name);
    infoMap.put("piece length", info.pieceLength);
    infoMap.put("pieces", info.pieces);

    byte[] encodedInfo = BENCODE.encode(infoMap);

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

@Getter
@AllArgsConstructor
class MetaInfo {
  String announce;
  Info info;
}

@Getter
@AllArgsConstructor
class Info {
  long length;
  String name;
  long pieceLength;
  ByteBuffer pieces;
}

@Getter
@AllArgsConstructor
class TrackerResponse {
  long interval;
  ByteBuffer peers;
}

@Getter
@AllArgsConstructor
class Peer {
  InetAddress ip;
  int port;

  @Override
  public String toString() {
    return String.format("%s:%s", ip.getHostAddress(), port);
  }
}

