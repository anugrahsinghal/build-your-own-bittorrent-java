import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Main {
  private static final Gson gson = new Gson();
  private static final Bencode bencode = new Bencode();
  private static final Bencode bencodeForTorrentFile = new Bencode(true);

  public static void main(String[] args) throws Exception {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
//    System.out.println("Logs from your program will appear here!");
    String command = args[0];
    if ("decode".equals(command)) {
      //  Uncomment this block to pass the first stage
      String bencodedValue = args[1];
      Object decoded;
      try {
//        final Type type = bencode.type(bencodedValue.getBytes());
//        System.out.println("type = " + type.toString());

        decoded = bencode.decode(bencodedValue.getBytes(), bencode.type(bencodedValue.getBytes()));

//        System.out.println("decoded = " + decoded.getClass());

      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(gson.toJson(decoded));

    } else if ("info".equals(command)) {
      final byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

      final Map<String, Object> torrentFileDecoded = bencodeForTorrentFile.decode(torrentFile, Type.DICTIONARY);

      final ByteBuffer announce = (ByteBuffer) torrentFileDecoded.get("announce");
      System.out.printf("Tracker URL: %s\n", new String(announce.array()));

      final Map<String, Object> info = (Map<String, Object>) torrentFileDecoded.get("info");

      System.out.printf("Length: %s\n", info.get("length"));

      final String infoHash = getInfoHash(torrentFileDecoded);
      System.out.printf("Info Hash: %s\n", infoHash);

      System.out.printf("Piece Length: %s\n", info.get("piece length"));

      System.out.println("Piece Hashes:");
      List<String> pieceHashes = getPieceHashes(torrentFileDecoded);
      pieceHashes.forEach(System.out::println);
      //e876f67a2a8886e8f36b136726c30fa29703022d
      //6e2275e604a0766656736e81ff10b55204ad8d35
      //f00d937a0213df1982bc8d097227ad9e909acc17
    } else {
      System.out.println("Unknown command: " + command);
    }

  }

  public static List<String> getPieceHashes(Map<String, Object> torrentFileDecoded) {
    List<String> pieceHashes = new ArrayList<>();

    final ByteBuffer pieces = (ByteBuffer) ((Map<?, ?>) torrentFileDecoded.get("info")).get("pieces");
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

  static void printInfoDict(Map<String, Object> info) throws IOException {
    for (Map.Entry<String, Object> kv : info.entrySet()) {
      final String key = kv.getKey();
      final Object value = kv.getValue();
      try (
          ByteArrayOutputStream bos = new ByteArrayOutputStream();
          ObjectOutputStream oos = new ObjectOutputStream(bos)
      ) {
        oos.writeObject(value);

        final byte[] valueAsBytes = bos.toByteArray();

        System.out.printf("%s -- value %s\n", key, Arrays.toString(valueAsBytes));
      }
    }
  }

  private static String getInfoHash(Map<String, Object> torrentFileDecoded) {
    final byte[] encodedInfo = bencodeForTorrentFile.encode((Map<?, ?>) torrentFileDecoded.get("info"));

    final String infoHash = getHashedValue(encodedInfo);

    return infoHash;
  }

  static String decodeBencode(String bencodedString) {
    if (Character.isDigit(bencodedString.charAt(0))) {
      int firstColonIndex = 0;
      for (int i = 0; i < bencodedString.length(); i++) {
        if (bencodedString.charAt(i) == ':') {
          firstColonIndex = i;
          break;
        }
      }
      int length = Integer.parseInt(bencodedString.substring(0, firstColonIndex));
      return bencodedString.substring(firstColonIndex + 1, firstColonIndex + 1 + length);
    } else {
      throw new RuntimeException("Only strings are supported at the moment");
    }
  }

  public static String getHashedValue(byte[] input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.update(input);
      byte[] digest = md.digest();

      return asHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }

  }

  private static String asHex(byte[] digest) {
    StringBuilder hexString = new StringBuilder();

    for (byte b : digest) {
      hexString.append(String.format("%02x", b));
    }

    return hexString.toString();
  }

}
