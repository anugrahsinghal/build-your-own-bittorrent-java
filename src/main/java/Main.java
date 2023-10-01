import com.dampcake.bencode.Bencode;
import com.dampcake.bencode.Type;
import com.google.gson.Gson;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class Main {
  private static final Gson gson = new Gson();
  private static final Bencode bencode = new Bencode();

  public static void main(String[] args) throws Exception {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
//    System.out.println("Logs from your program will appear here!");
    String command = args[0];
    if ("decode".equals(command)) {
      //  Uncomment this block to pass the first stage
      String bencodedValue = args[1];
      Object decoded;
      try {
        decoded = bencode.decode(bencodedValue.getBytes(), bencode.type(bencodedValue.getBytes()));
      } catch (RuntimeException e) {
        System.out.println(e.getMessage());
        return;
      }
      System.out.println(gson.toJson(decoded));

    } else if ("info".equals(command)) {
      final byte[] torrentFile = Files.readAllBytes(Paths.get(args[1]));

      final Map<String, Object> torrentFileDecoded = bencode.decode(torrentFile, Type.DICTIONARY);

      final Object url = torrentFileDecoded.get("announce");
      System.out.printf("Tracker URL: %s\n", url);

      final Map<String, Object> info = (Map<String, Object>) torrentFileDecoded.get("info");

      System.out.printf("Length: %s\n", info.get("length"));

    } else {
      System.out.println("Unknown command: " + command);
    }

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

}
