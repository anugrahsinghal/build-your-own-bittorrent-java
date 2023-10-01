import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


class ConnectionPool {

  private final BlockingQueue<Socket> connectionPool;
  private final List<Socket> usedConnections;
  private final Map<Socket, Boolean> preDownloadMarker;

  private ConnectionPool(BlockingQueue<Socket> connectionPool) {
    this.connectionPool = connectionPool;
    this.usedConnections = new CopyOnWriteArrayList<>();
    this.preDownloadMarker = new ConcurrentHashMap<>();
  }

  public static ConnectionPool create(List<Peer> peers) {

    BlockingQueue<Socket> pool = new LinkedBlockingQueue<>();
    for (Peer peer : peers) {
      pool.add(createConnection(peer.ip.getHostAddress(), peer.port));
    }

    return new ConnectionPool(pool);
  }

  private static Socket createConnection(String ip, int port) {
    return Networks.createConnection(ip, port);
  }

  public Socket getConnection(MetaInfo metaInfo) throws InterruptedException {
    while (connectionPool.isEmpty()) {
      Thread.sleep(10);
    }
    Socket connection = connectionPool.poll();

    if (!preDownloadMarker.containsKey(connection)) {
      Networks.preDownload(connection, metaInfo);
      preDownloadMarker.put(connection, true);
    }

    usedConnections.add(connection);
    return connection;
  }

  public boolean releaseConnection(Socket connection) {
    connectionPool.add(connection);
    return usedConnections.remove(connection);
  }
  
  public boolean closeConnections() throws IOException {
    if (!usedConnections.isEmpty()) {
      return false;
    }
    for (Socket socket : connectionPool) {
      socket.close();
    }
    return true;
  }

}

@AllArgsConstructor
class DownloadTask implements Callable<PieceByte> {

  int pieceId;
  int pieceLength;
  List<String> pieceHashes;
  MetaInfo metaInfo;
  ConnectionPool connectionPool;

  @Override
  public PieceByte call() throws Exception {
    Socket socket = connectionPool.getConnection(metaInfo);
    byte[] downloadedPiece = Networks.downloadPiece(pieceId, pieceLength, socket, pieceHashes);
    connectionPool.releaseConnection(socket);
    return new PieceByte(pieceId, downloadedPiece);
  }
}

@AllArgsConstructor
@Getter
class PieceByte {
  int pieceId;
  byte[] data;
}
