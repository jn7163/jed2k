package org.dkf.jed2k;

import org.bitlet.weupnp.GatewayDevice;
import org.bitlet.weupnp.GatewayDiscover;
import org.bitlet.weupnp.PortMappingEntry;
import org.dkf.jed2k.alert.*;
import org.dkf.jed2k.disk.AsyncOperationResult;
import org.dkf.jed2k.disk.FileHandler;
import org.dkf.jed2k.disk.TransferCallable;
import org.dkf.jed2k.exception.BaseErrorCode;
import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.kad.DhtTracker;
import org.dkf.jed2k.kad.KadSearchEntryDistinct;
import org.dkf.jed2k.kad.Listener;
import org.dkf.jed2k.pool.BufferPool;
import org.dkf.jed2k.pool.Pool;
import org.dkf.jed2k.protocol.Endpoint;
import org.dkf.jed2k.protocol.Hash;
import org.dkf.jed2k.protocol.SearchEntry;
import org.dkf.jed2k.protocol.kad.KadId;
import org.dkf.jed2k.protocol.kad.KadSearchEntry;
import org.dkf.jed2k.protocol.server.search.SearchRequest;
import org.dkf.jed2k.protocol.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Session extends Thread {
    private static Logger log = LoggerFactory.getLogger(Session.class);
    Selector selector = null;
    private ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<Runnable>();
    ServerConnection serverConection = null;
    private ServerSocketChannel ssc = null;

    Map<Hash, Transfer> transfers = new HashMap<Hash, Transfer>();
    ArrayList<PeerConnection> connections = new ArrayList<PeerConnection>(); // incoming connections
    Settings settings = null;
    long lastTick = Time.currentTime();
    HashMap<Integer, Hash> callbacks = new HashMap<Integer, Hash>();
    private ByteBuffer skipDataBuffer = null;
    private byte[] zBuffer = null;
    long zBufferLastAllocatedTime = 0;
    private BufferPool bufferPool = null;
    private ExecutorService diskIOService = Executors.newSingleThreadExecutor();
    private ExecutorService upnpService = Executors.newSingleThreadExecutor();
    private AtomicBoolean finished = new AtomicBoolean(false);
    private boolean aborted = false;
    private Statistics accumulator = new Statistics();
    private GatewayDiscover discover = new GatewayDiscover();
    private GatewayDevice device = null;

    /**
     * async disk io futures
     */
    LinkedList<Future<AsyncOperationResult> > aioFutures = new LinkedList<Future<AsyncOperationResult>>();

    /**
     * async originators
     */
    LinkedList<Transfer> aioOrigins = new LinkedList<>();

    /**
     * external DHT tracker object
     */
    private WeakReference<DhtTracker> dhtTracker = new WeakReference<DhtTracker>(null);

    /**
     * sources search result callback
     */
    private static class DhtSourcesCallback implements Listener {
        final Session session;
        final WeakReference<Transfer> weakTransfer;

        public DhtSourcesCallback(final Session session, final Transfer t) {
            this.session = session;
            this.weakTransfer = new WeakReference<Transfer>(t);
        }

        @Override
        public void process(final List<KadSearchEntry> data) {
            session.commands.add(new Runnable() {
                @Override
                public void run() {
                    Transfer transfer = weakTransfer.get();
                    if (transfer == null) {
                        log.debug("[session] transfer not exists for searched result, just skip it");
                        return;
                    }

                    for(final KadSearchEntry kse: data) {
                        KadId target = kse.getKid();
                        assert target != null; // actually impossible

                        if (transfer == null && transfer.wantMorePeers()) {
                            log.debug("[session] transfer for {} not exists", target);
                            continue;
                        };

                        int ip = 0;
                        int sourceType = 0;
                        int sourcePort = 0;
                        int sourceUPort = 0;
                        int lowId = 0;
                        int serverIp = 0;
                        int serverPort = 0;
                        KadId id = null;
                        int cryptOptions = 0;

                        try {
                            for (final Tag t : kse.getInfo()) {
                                switch (t.getId()) {
                                    case Tag.TAG_SOURCETYPE:
                                        sourceType = t.intValue();
                                        break;
                                    case Tag.TAG_SOURCEIP:
                                        ip = Utils.ntohl(t.intValue());
                                        break;
                                    case Tag.TAG_SOURCEPORT:
                                        sourcePort = t.intValue();
                                        break;
                                    case Tag.TAG_CLIENTLOWID:
                                        lowId = t.intValue();
                                        break;
                                    case Tag.TAG_SOURCEUPORT:
                                        sourceUPort = t.intValue();
                                        break;
                                    case Tag.TAG_SERVERIP:
                                        serverIp = t.intValue();
                                        break;
                                    case Tag.TAG_SERVERPORT:
                                        serverPort = t.intValue();
                                        break;
                                    case Tag.TAG_BUDDYHASH:
                                        try {
                                            id = new KadId(Hash.fromString(t.stringValue()));
                                        } catch(JED2KException e) {
                                            log.warn("[session] unable to extract buddy getHash {}", e);
                                        }
                                        break;
                                    case Tag.TAG_ENCRYPTION:
                                        cryptOptions = t.intValue();
                                        break;
                                    default:
                                        log.debug("[session] unhandled KAD search tag {}", t);
                                        break;
                                }
                            }
                        } catch(JED2KException e) {
                            log.error("[session] processing kad search sources result failed {}", e);
                        }

                        assert transfer != null;
                        // process here only non-firewalled sources
                        if (ip != 0 && sourcePort != 0 && (sourceType == 1 || sourceType == 4)) {
                            try {
                                transfer.addPeer(new Endpoint(ip, sourcePort), PeerInfo.DHT);
                            } catch(JED2KException e) {
                                log.error("[session] unable to add peer {}:{} to transfer {} with error {}", ip, sourcePort, transfer, e);
                            }
                        }
                    }
                }
            });
        }
    }

    private static class DhtDebugCallback implements Listener {

        @Override
        public void process(List<KadSearchEntry> data) {
            log.info("[session] DHT debug callback results size {}", data.size());
            for(final KadSearchEntry e: data) {
                log.info("entry: {}", e);
            }
        }
    }

    private static class DhtKeywordsCallback implements Listener {
        final Session session;
        private final long minSize;
        private final long maxSize;
        private final int sources;
        private final int completeSources;

        public DhtKeywordsCallback(final Session session, long minSize, long maxSize, int sources, int completeSources) {
            this.session = session;
            this.minSize = minSize;
            this.maxSize = maxSize;
            this.sources = sources;
            this.completeSources = completeSources;
        }

        @Override
        public void process(List<KadSearchEntry> data) {
            List<SearchEntry> filtered = new LinkedList<>();
            List<SearchEntry> res = KadSearchEntryDistinct.distinct(data);
            for(final SearchEntry e: res) {
                if (minSize > 0 && e.getFileSize() < minSize) continue;
                if (maxSize > 0 && e.getFileSize() > maxSize) continue;
                if (sources > 0 && e.getSources() < sources) continue;
                if (completeSources > 0 && e.getCompleteSources() < completeSources) continue;
                filtered.add(e);
            }

            session.pushAlert(new SearchResultAlert(filtered, false));
        }
    }

    // from last established server connection
    int clientId    = 0;
    int tcpFlags    = 0;
    int auxPort     = 0;

    private BlockingQueue<Alert> alerts = new LinkedBlockingQueue<Alert>();

    public Session(final Settings st) {
        // TODO - validate settings before usage
        settings = st;
        bufferPool = new BufferPool(st.bufferPoolSize);
    }

    void closeListenSocket() {
        try {
            if (ssc != null) {
                ssc.close();
            }
        } catch(IOException e) {
            log.error("unable to close listen socket {}", e);
        } finally {
            ssc = null;
        }
    }

    /**
     * start listening server socket
     */
    private void listen() {
        closeListenSocket();

        try {
            if (settings.listenPort > 0) {
                assert selector != null;
                log.info("start listening on port {}", settings.listenPort);
                ssc = ServerSocketChannel.open();
                ssc.socket().bind(new InetSocketAddress(settings.listenPort));
                ssc.configureBlocking(false);
                ssc.register(selector, SelectionKey.OP_ACCEPT);
                pushAlert(new ListenAlert("", settings.listenPort));
            } else {
                log.info("no listen mode, listen port is {}", settings.listenPort);
            }
        }
        catch(IOException e) {
            log.error("[listen] failed {}", e);
            closeListenSocket();
            pushAlert(new ListenAlert(e.getMessage(), settings.listenPort));
        }
        catch(IllegalArgumentException e) {
            log.error("[listen] illegal argument exception {}", e);
            closeListenSocket();
            pushAlert(new ListenAlert(e.getMessage(), settings.listenPort));
        }
        catch(Exception e) {
            log.error("[listen] unexpected exception {}", e);
            closeListenSocket();
            pushAlert(new ListenAlert(e.getMessage(), settings.listenPort));
        }
    }

    /**
     * synchronized session internal processing method
     * @param ec
     * @throws IOException
     */
    private synchronized void on_tick(BaseErrorCode ec, int channelCount) {

        if (channelCount != 0) {
            // process channels
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                if (key.isValid()) {

                    if(key.isAcceptable()) {
                        // a connection was accepted by a ServerSocketChannel.
                        //log.trace("Key is acceptable");
                        incomingConnection();
                    } else if (key.isConnectable()) {
                        // a connection was established with a remote server/peer.
                        //log.trace("Key is connectable");
                        ((Connection)key.attachment()).onConnectable();
                    } else if (key.isReadable()) {
                        // a channel is ready for reading
                        //log.trace("Key is readable");
                        ((Connection)key.attachment()).onReadable();
                    } else if (key.isWritable()) {
                        // a channel is ready for writing
                        //log.trace("Key is writeable");
                        ((Connection)key.attachment()).onWriteable();
                    }
                }

                keyIterator.remove();
            }
        }

        /**
         * handle user's command and process internal tasks in
         * transfers, peers and other structures every 1 second
         */
        long tickIntervalMs = Time.currentTime() - lastTick;
        if (tickIntervalMs >= 1000) {
            lastTick = Time.currentTime();
            secondTick(Time.currentTime(), tickIntervalMs);
        }
    }

    public void secondTick(long currentSessionTime, long tickIntervalMS) {

        Iterator<Map.Entry<Hash, Transfer>> itr = transfers.entrySet().iterator();

        while(itr.hasNext()) {
            itr.next().getValue().secondTick(accumulator, tickIntervalMS);
        }

        // second tick on server connection
        if (serverConection != null) serverConection.secondTick(tickIntervalMS);

        processDiskTasks();

        // TODO - run second tick on peer connections
        // execute user's commands
        Runnable r = commands.poll();
        while(r != null) {
            r.run();
            r = commands.poll();
        }

        accumulator.secondTick(tickIntervalMS);
        connectNewPeers();
        //log.trace(bufferPool.toString());
    }

    @Override
    public void run() {
        try {
            log.debug("Session started");
            selector = Selector.open();
            listen();

            while(!aborted && !interrupted()) {
                int channelCount = selector.select(1000);
                Time.updateCachedTime();
                on_tick(ErrorCode.NO_ERROR, channelCount);
            }
        }
        catch(IOException e) {
            log.error("[run] session interrupted with error {}", e);
        }
        finally {
            log.info("Session is closing");
            commands.clear();

            try {
                if (selector != null) selector.close();
            }
            catch(IOException e) {
                log.error("[run] close selector failed {}", e);
            }

            // close listen socket
            if (ssc != null) {
                try {
                    ssc.close();
                } catch(IOException e) {
                    log.error("listen socket close error {}", e);
                }
            }

            // stop server connection
            if (serverConection != null) serverConection.close(ErrorCode.SESSION_STOPPING);

            // abort all transfers
            for(final Transfer t: transfers.values()) {
                t.abort(false, true);   // hard abort tasks to exit as soon as possible. buffers leak possible
            }

            transfers.clear();

            // 5 seconds for close all transfers
            for(int i = 0; i < 50; ++i) {
                log.info("process disk tasks {} iteration", i);
                processDiskTasks();
                if (aioFutures.isEmpty()) break;

                try {
                    Thread.sleep(100);
                } catch(Exception e) {
                    log.error("sleep error {}", e);
                }
            }

            if (!aioFutures.isEmpty()) {
                log.warn("not all futures completed");
                for(final Transfer t: transfers.values()) {
                    log.warn("transfer {} is not finished", t.getHash());
                }
            }

            ArrayList<PeerConnection> localConnections = (ArrayList<PeerConnection>) connections.clone();
            for(final PeerConnection c: localConnections) {
                c.close(ErrorCode.SESSION_STOPPING);
            }

            localConnections.clear();
            connections.clear();

            // stop service
            diskIOService.shutdown();
            upnpService.shutdown();
            stopUPnPImpl("TCP");
            stopUPnPImpl("UDP");
            log.info("Session finished");
            finished.set(true);
        }
    }

    /**
     * create new peer connection for incoming connection
     */
    void incomingConnection() {
        try {
            SocketChannel sc = ssc.accept();
            PeerConnection p = PeerConnection.make(sc, this);
            connections.add(p);
        }
        catch(IOException e) {
            log.error("Socket accept failed {}", e);
        }
        catch (JED2KException e) {
            log.error("Peer connection creation failed {}", e);
        }
    }

    void closeConnection(PeerConnection p) {
        connections.remove(p);
    }

    void openConnection(Endpoint point) throws JED2KException {
        if (findPeerConnection(point) == null) {
            PeerConnection p = PeerConnection.make(Session.this, point, null, null);
            if (p != null) connections.add(p);
            p.connect();
        }
    }

    public void connectoTo(final String id, final InetSocketAddress point) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                if (serverConection != null) {
                    serverConection.close(ErrorCode.NO_ERROR);
                }

                try {
                    serverConection = ServerConnection.makeConnection(id, Session.this);
                    serverConection.connect(point);
                    Endpoint endpoint = new Endpoint(point);
                    log.debug("connect to server {}", endpoint);
                } catch(JED2KException e) {
                    // emit alert - connect to server failed
                    log.error("server connection failed {}", e);
                }
            }
        });
    }

    public void connectoTo(final String id, final String host, final int port) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                try {
                    final InetSocketAddress addr = new InetSocketAddress(host, port);

                    if (serverConection != null) {
                        serverConection.close(ErrorCode.NO_ERROR);
                    }

                    try {
                        serverConection = ServerConnection.makeConnection(id, Session.this);
                        serverConection.connect(addr);
                        Endpoint endpoint = new Endpoint(addr);
                        pushAlert(new ServerConnectionAlert(id));
                        log.debug("connect to server {}", endpoint);
                    } catch(JED2KException e) {
                        // emit alert - connect to server failed
                        log.error("server connection failed {}", e);
                    }
                }
                catch(Exception e) {
                    log.error("Illegal input parameters {} or {}", host, port);
                }
            }
        });
    }

    public void disconnectFrom() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                if (serverConection != null) {
                    serverConection.close(ErrorCode.NO_ERROR);
                }
            }
        });
    }

    synchronized public String getConnectedServerId() {
        if (serverConection != null && serverConection.isHandshakeCompleted()) return serverConection.getIdentifier();
        return "";
    }

    public void search(final SearchRequest value) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                if (serverConection != null) {
                    serverConection.search(value);
                }
            }
        });
    }

    public void searchDhtKeyword(final String keyword, final long minSize, final long maxSize, final int sources, final int completeSources) {
        final Session s = this;
        commands.add(new Runnable() {
            @Override
            public void run() {
                DhtTracker tracker = dhtTracker.get();
                if (tracker != null && !tracker.isAborted()) {
                    try {
                        tracker.searchKeywords(keyword, new DhtKeywordsCallback(s, minSize, maxSize, sources, completeSources));
                    } catch(JED2KException e) {
                        log.error("[session] unable to start search keyword {} in DHT {}", keyword, e);
                    }
                }
            }
        });
    }


    public void searchMore() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                if (serverConection != null) {
                    serverConection.searchMore();
                }
            }
        });
    }

    // TODO - remove only
    public void connectToPeer(final Endpoint point) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                    try {
                        PeerConnection pc = PeerConnection.make(Session.this, point, null, null);
                        connections.add(pc);
                        pc.connect(point.toInetSocketAddress());
                    } catch(JED2KException e) {
                        log.error("new peer connection failed {}", e);
                    }
            }
        });
    }

    private PeerConnection findPeerConnection(Endpoint endpoint) {
        for(PeerConnection p: connections) {
            if (p.hasEndpoint() && endpoint.compareTo(p.getEndpoint()) == 0) return p;
        }

        return null;
    }

    /**
     *
     * @param s contains configuration parameters for session
     */
    public void configureSession(final Settings s) {
    	commands.add(new Runnable() {
			@Override
			public void run() {
				settings = s;
				listen();
			}
    	});
    }

    public void pushAlert(Alert alert) {
        assert(alert != null);
        try {
            alerts.put(alert);
        }
        catch (InterruptedException e) {
            log.error("push alert interrupted {}", e);
        }
    }

    public Alert  popAlert() {
        return alerts.poll();
    }

    public long getCurrentTime() {
        return lastTick;
    }

    /**
     * create new transfer in session or return previous
     * method synchronized with session second tick method
     * @param h getHash of file(transfer)
     * @param size of file
     * @return TransferHandle with valid transfer of without
     */
    public final synchronized TransferHandle addTransfer(Hash h, long size, File file) throws JED2KException {
        Transfer t = transfers.get(h);

        if (t == null) {
            t = new Transfer(this, new AddTransferParams(h, Time.currentTimeMillis(), size, file, false));
            transfers.put(h, t);
        }

        return new TransferHandle(this, t);
    }

    /**
     * the same as previous instead of external file handler
     * @param h
     * @param size
     * @param handler
     * @return
     * @throws JED2KException
     */
    public final synchronized TransferHandle addTransfer(Hash h, long size, FileHandler handler) throws JED2KException {
        Transfer t = transfers.get(h);

        if (t == null) {
            t = new Transfer(this, new AddTransferParams(h, Time.currentTimeMillis(), size, handler, false));
            transfers.put(h, t);
        }

        return new TransferHandle(this, t);
    }

    /**
     * create new transfer in session or return previously created transfer
     * using add transfer parameters structure with or without resume data block
     * @param atp transfer parameters with or without resume data
     * @return transfer handle
     * @throws JED2KException
     */
    public final synchronized  TransferHandle addTransfer(final AddTransferParams atp) throws JED2KException {
        Transfer t = transfers.get(atp.getHash());

        if (t == null) {
            t = new Transfer(this, atp);
            transfers.put(atp.getHash(), t);
        }

        return new TransferHandle(this, t);
    }

    public final synchronized TransferHandle findTransfer(final Hash h) {
        return new TransferHandle(this, transfers.get(h));
    }

    public void removeTransfer(final Hash h, final boolean deleteFile) {
        commands.add(new Runnable() {
            @Override
            public void run() {
                    Transfer t = transfers.get(h);
                    if (t != null) {
                        t.abort(deleteFile, false); // abort transfer, but do not cancel disk tasks to avoid buffers leaking
                        transfers.remove(t.getHash());
                        pushAlert(new TransferRemovedAlert(h));
                    }
            }
        });
    }

    public final synchronized List<TransferHandle> getTransfers() {
        LinkedList<TransferHandle> handles = new LinkedList<TransferHandle>();
        for(final Transfer t: transfers.values()) {
            /*if (!t.isAborted())*/ handles.add(new TransferHandle(this, t));
        }

        return handles;
    }

    void sendSourcesRequest(final Hash h, final long size) {
        if (serverConection != null) serverConection.sendFileSourcesRequest(h, size);
    }

    void sendDhtSourcesRequest(final Hash h, final long size, final Transfer t) {
        if (dhtTracker != null) {
            try {
                DhtTracker dht = dhtTracker.get();
                if (dht != null) dht.searchSources(h, size, new DhtSourcesCallback(this, t));
            } catch(JED2KException e) {
                log.error("[session] dht search sources error {}", e);
            }
        }
    }

    /**
     * executes each second
     * traverse transfers list and try to connect new peers if limits not exceeded and transfer want more peers
     */
    void connectNewPeers() {
        int stepsSinceLastConnect = 0;
        int maxConnectionsPerSecond = settings.maxConnectionsPerSecond;
        int numTransfers = transfers.size();
        boolean enumerateCandidates = true;

        if (numTransfers > 0 && connections.size() < settings.sessionConnectionsLimit) {
            //log.finest("connectNewPeers with transfers count " + numTransfers);
            while (enumerateCandidates) {
                for (Map.Entry<Hash, Transfer> entry : transfers.entrySet()) {
                    Transfer t = entry.getValue();

                    if (t.wantMorePeers()) {
                        try {
                            if (t.tryConnectPeer(Time.currentTime())) {
                                --maxConnectionsPerSecond;
                                stepsSinceLastConnect = 0;
                            }
                        } catch (JED2KException e) {
                            log.error("exception on connect new peer {}", e);
                        }
                    }

                    ++stepsSinceLastConnect;

                    // if we have gone two whole loops without
                    // handing out a single connection, break
                    if (stepsSinceLastConnect > numTransfers*2) {
                        enumerateCandidates = false;
                        break;
                    }

                    // if we should not make any more connections
                    // attempts this tick, abort
                    if (maxConnectionsPerSecond == 0) {
                        enumerateCandidates = false;
                        break;
                    }
                }

                // must not happen :) but still
                if (transfers.isEmpty()) break;
            }
        }
    }

    /**
     * allocate new fixed size byte buffer from session's buffer pool
     * @return byte buffer from common session buffer pool
     */
    public ByteBuffer allocatePoolBuffer() throws JED2KException {
        return bufferPool.allocate();
    }

    /**
     * sometimes we need to skip some data received from peer
     * skip data buffer is one shared data buffer for all connections
     * @return byte buffer
     */
    ByteBuffer allocateSkipDataBufer() {
        if (skipDataBuffer == null) {
            skipDataBuffer = ByteBuffer.allocate(Constants.BLOCK_SIZE_INT);
        }

        return skipDataBuffer.duplicate();
    }

    Pool<ByteBuffer> getBufferPool() {
        return bufferPool;
    }

    /**
     * provide one per session temporary buffer for inflate z data
     * @return common z buffer for decompress compressed data
     */
    byte[] allocateTemporaryInflateBuffer() {
        zBufferLastAllocatedTime = Time.currentTime();
        if (zBuffer == null) zBuffer = new byte[Constants.BLOCK_SIZE_INT];
        return zBuffer;
    }

    /**
     * execute async disk operation
     * @param task special task
     * @return future
     */
    public void submitDiskTask(TransferCallable<AsyncOperationResult> task) {
        assert task.getTransfer() != null;
        aioFutures.add(diskIOService.submit(task));
        aioOrigins.add(task.getTransfer());
        assert aioFutures.size() == aioOrigins.size();
    }

    public void removeDiskTask(final Transfer t) {
        assert aioFutures.size() == aioOrigins.size();

        Iterator<Transfer> trItr = aioOrigins.iterator();
        Iterator<Future<AsyncOperationResult>> fItr = aioFutures.iterator();
        while(trItr.hasNext()) {
            Future<AsyncOperationResult> future = fItr.next();
            Transfer tran = trItr.next();

            if (tran == t) {
                future.cancel(false);
                trItr.remove();
                fItr.remove();
            }
        }
    }

    private void processDiskTasks() {
        assert aioOrigins.size() == aioFutures.size();

        while(!aioFutures.isEmpty()) {
            Future<AsyncOperationResult> res = aioFutures.peek();
            if (!res.isDone()) break;
            res = aioFutures.poll();
            Transfer t = aioOrigins.poll();

            try {
                res.get().onCompleted();
            } catch (InterruptedException e) {
                log.warn("second tick aio InterruptedException {}", e);
            } catch (ExecutionException e) {
                log.warn("second tick aio ExecutionException {}", e);
            } catch(Exception e) {
                log.error("general error on processing async operation result {}", e);
            }
        }

        assert aioFutures.size() == aioOrigins.size();
    }

    @Override
    public String toString() {
        return "Session";
    }

    public Hash getUserAgent() { return settings.userAgent; }
    public int getClientId() { return clientId; }
    public int getListenPort() { return settings.listenPort; }
    public String getClientName() { return settings.clientName; }
    public String getModName() { return settings.modName; }
    public int getAppVersion() { return settings.version; }
    public int getCompressionVersion() { return settings.compressionVersion; }
    public int getModMajorVersion() { return settings.modMajor; }
    public int getModMinorVersion() { return settings.modMinor; }
    public int getModBuildVersion() { return settings.modBuild; }

    /**
     * thread safe
     * @return status of session
     */
    public final boolean isFinished() {
        return finished.get();
    }

    /**
     * stop main session cycle
     * guarantees all previous commands were completed
     */
    public void abort() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                aborted = true;
            }
        });
    }

    /**
     * save resume data on all transfers needs to save resume data
     */
    public void saveResumeData() {
        commands.add(new Runnable() {
            @Override
            public void run() {
                for(final Transfer t: transfers.values()) {
                    if (t.isNeedSaveResumeData()) {
                        try {
                            AddTransferParams atp = new AddTransferParams(t.getHash(), t.getCreateTime(), t.size(), t.getFile(), t.isPaused());
                            atp.resumeData.setData(t.resumeData());
                            pushAlert(new TransferResumeDataAlert(t.getHash(), atp));
                        } catch(JED2KException e) {
                            log.error("prepare resume data for {} failed {}", t.getHash(), e);
                        }
                    }
                }
            }
        });
    }

    synchronized  public Pair<Long, Long> getDownloadUploadRate() {
        long dr = accumulator.downloadRate();
        long ur = accumulator.uploadRate();
        return Pair.make(dr, ur);
    }

    public void startUPnP() throws JED2KException {
        try {
            if (upnpService.isShutdown()) return;

            upnpService.submit(new Runnable() {

                @Override
                public void run() {
                    assert discover != null;
                    BaseErrorCode ec = ErrorCode.NO_ERROR;
                    // TODO - fix unsynchronized access to settings
                    int port = settings.listenPort;
                    try {
                        discover.discover();
                        device = discover.getValidGateway();
                        if (device != null) {
                            final String[] protocols = {"TCP", "UDP"};
                            for (final String protocol : protocols) {
                                PortMappingEntry portMapping = new PortMappingEntry();
                                if (!device.getSpecificPortMappingEntry(port, protocol, portMapping)) {
                                    InetAddress localAddress = device.getLocalAddress();
                                    if (device.addPortMapping(port, port, localAddress.getHostAddress(), protocol, "JED2K")) {
                                        // ok, mapping added
                                        log.info("[session] port mapped {} for {}", port, protocol);
                                    } else {
                                        log.info("[session] port {} mapping error for {}", port, protocol);
                                        ec = ErrorCode.PORT_MAPPING_ERROR;
                                    }
                                } else {
                                    log.debug("[session] port {} already mapped for {}", port, protocol);
                                    ec = ErrorCode.PORT_MAPPING_ALREADY_MAPPED;
                                }
                            }
                        } else {
                            log.debug("[session] can not find gateway device");
                            ec = ErrorCode.PORT_MAPPING_NO_DEVICE;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        ec = ErrorCode.PORT_MAPPING_IO_ERROR;
                    } catch (SAXException e) {
                        e.printStackTrace();
                        ec = ErrorCode.PORT_MAPPING_SAX_ERROR;
                    } catch (ParserConfigurationException e) {
                        e.printStackTrace();
                        ec = ErrorCode.PORT_MAPPING_CONFIG_ERROR;
                    } catch (Exception e) {
                        e.printStackTrace();
                        ec = ErrorCode.PORT_MAPPING_EXCEPTION;
                    }

                    pushAlert(new PortMapAlert(port, port, ec));
                }
            });
        } catch(RejectedExecutionException e) {
            throw new JED2KException(ErrorCode.PORT_MAPPING_COMMAND_REJECTED);
        }
    }

    public void stopUPnP() throws JED2KException {
        try {
            if (upnpService.isShutdown()) return;
            upnpService.submit(new Runnable() {
                @Override
                public void run() {
                    stopUPnPImpl("TCP");
                    stopUPnPImpl("UDP");
                    device = null;
                }
            });
        } catch(RejectedExecutionException e) {
            throw new JED2KException(ErrorCode.PORT_MAPPING_COMMAND_REJECTED);
        }
    }

    private void stopUPnPImpl(final String protocol) {
        if (device != null) {
            try {
                if (device.deletePortMapping(settings.listenPort, protocol)) {
                    log.info("port mapping removed {}", settings.listenPort);
                } else {
                    log.error("port mapping removing failed");
                }
            } catch (IOException e) {
                e.printStackTrace();
                log.error("[session] unmap port I/O error {}", e);
            } catch (SAXException e) {
                e.printStackTrace();
                log.error("[session] unmap port SAX error {}", e);
            }
            catch(Exception e) {
                e.printStackTrace();
                log.error("[session] unmap port error {}", e);
            }
        }
    }

    /**
     * debug only method to run search source from external process
     * @param h getHash of file
     * @param size size of file
     */
    public synchronized void dhtDebugSearch(final Hash h, long size) {
        try {
            DhtTracker tracker = dhtTracker.get();
            if (tracker != null) {
                tracker.searchSources(h, size, new DhtDebugCallback());
            } else {
                log.warn("[session] DHT is not running, but search sources requested");
            }
        } catch(JED2KException e) {
            log.error("[session] unable to start debug search {}", e);
        }
    }

    /**
     * add or remove DHT tracker from session
     * @param tracker external DHT tracker object or null
     */
    public synchronized void setDhtTracker(final DhtTracker tracker) {
        dhtTracker = new WeakReference<DhtTracker>(tracker);
    }

    public synchronized DhtTracker getDhtTracker() {
        return dhtTracker.get();
    }
}
