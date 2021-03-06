/*
 * Galaxy
 * Copyright (C) 2012 Parallel Universe Software Co.
 * 
 * This file is part of Galaxy.
 *
 * Galaxy is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as 
 * published by the Free Software Foundation, either version 3 of 
 * the License, or (at your option) any later version.
 *
 * Galaxy is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public 
 * License along with Galaxy. If not, see <http://www.gnu.org/licenses/>.
 */
package co.paralleluniverse.galaxy.core;

import co.paralleluniverse.common.MonitoringType;
import co.paralleluniverse.common.io.Checksum;
import co.paralleluniverse.common.io.DoubleHasher;
import co.paralleluniverse.common.io.Persistable;
import co.paralleluniverse.common.io.Persistables;
import co.paralleluniverse.common.io.VersionedPersistable;
import static co.paralleluniverse.common.logging.LoggingUtils.hex;
import co.paralleluniverse.common.util.DegenerateInvocationHandler;
import co.paralleluniverse.common.util.Enums;
import co.paralleluniverse.galaxy.CacheListener;
import co.paralleluniverse.galaxy.Cluster;
import co.paralleluniverse.galaxy.RefNotFoundException;
import co.paralleluniverse.galaxy.TimeoutException;
import co.paralleluniverse.galaxy.cluster.NodeChangeListener;
import co.paralleluniverse.galaxy.core.Message.LineMessage;
import co.paralleluniverse.galaxy.core.Transaction.RollbackInfo;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import com.googlecode.concurrentlinkedhashmap.Weigher;
import gnu.trove.iterator.TLongIterator;
import gnu.trove.iterator.TShortIterator;
import gnu.trove.procedure.TLongObjectProcedure;
import gnu.trove.set.hash.TShortHashSet;
import java.beans.ConstructorProperties;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.cliffc.high_scale_lib.NonBlockingHashMapLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;

/**
 * This is the big one. This is where most of Galaxy's logic is found. In particular it handles all of the MOESI protocol.<br/>
 * Other important classes:
 *
 * <ul> <li>{@link MainMemory}</li> <li>{@link co.paralleluniverse.galaxy.netty.UDPComm UDPComm}</li> <li>{@link AbstractCluster}</li>
 * </ul>
 *
 * @author pron
 */
public class Cache extends ClusterService implements MessageReceiver, NodeChangeListener {
    /*
     * To preserve memory ordering semantics, all messages from node N must be received and processed in the order in which they
     * were sent.
     */

    static final long MAX_RESERVED_REF_ID = 0xffffffffL;
    private static final boolean DIRTY_READS = true;
    private static final int SHARER_SET_DEFAULT_SIZE = 10;
    private static final Logger LOG = LoggerFactory.getLogger(Cache.class);
    private long timeout = 200000;
    private int maxItemSize = 1024;
    private boolean compareBeforeWrite = true;
    //
    private final Comm comm;
    private final Backup backup;
    private final CacheStorage storage;
    private final CacheMonitor monitor;
    private MessageReceiver receiver;
    private final boolean hasServer;
    //
    private final NonBlockingHashMapLong<CacheLine> owned;
    private final ConcurrentMap<Long, CacheLine> shared;
    private final NonBlockingHashMapLong<ArrayList<Op>> pendingOps;
    private final NonBlockingHashMapLong<HashSet<LineMessage>> pendingMessages;
    private ConcurrentLinkedDeque<CacheLine> freeLineList;
    private ConcurrentLinkedDeque<TShortHashSet> freeSharerSetList;
    private final ThreadLocal<Queue<Message>> shortCircuitMessage = new ThreadLocal<Queue<Message>>();
    private boolean reuseLines = true;
    private boolean reuseSharerSets = false;
    private boolean broadcastsRoutedToServer;
    private boolean rollbackSupported = true;
    private boolean synchronous = false;
    private Set<NodeEvent> nodeEvents = new CopyOnWriteArraySet<NodeEvent>();
    //
    private final IdAllocator idAllocator;
    private final NonBlockingHashMapLong<OwnerClock> ownerClocks;
    private final ThreadLocal<Boolean> recursive = new ThreadLocal<Boolean>();
    private final ThreadLocal<Boolean> inNodeEventHandler = new ThreadLocal<Boolean>();
    private final List<CacheListener> listeners = new CopyOnWriteArrayList<CacheListener>();
    //
    static final Object PENDING = new Object() {

        @Override
        public String toString() {
            return "PENDING";
        }

    };
    static final Object DIDNT_HANDLE = new Object();
    //
    private static final int LINE_NO_CHANGE = 0;
    private static final int LINE_STATE_CHANGED = 1;
    private static final int LINE_OWNER_CHANGED = 1 << 1;
    private static final int LINE_MODIFIED_CHANGED = 1 << 2;
    private static final int LINE_EVERYTHING_CHANGED = -1;
    //
    private static final long HIT_OR_MISS_OPS = Enums.setOf(Op.Type.GET, Op.Type.GETS, Op.Type.GETX, Op.Type.SET, Op.Type.DEL);
    private static final long FAST_TRACK_OPS = Enums.setOf(Op.Type.GET, Op.Type.GETS, Op.Type.GETX, Op.Type.SET, Op.Type.DEL, Op.Type.LSTN);
    private static final long LOCKING_OPS = Enums.setOf(Op.Type.GETS, Op.Type.GETX, Op.Type.SET, Op.Type.DEL);
    private static final long PUSH_OPS = Enums.setOf(Op.Type.PUSH, Op.Type.PUSHX);

    @ConstructorProperties({"name", "cluster", "comm", "storage", "backup", "monitoringType", "maxCapacity"})
    public Cache(String name, Cluster cluster, Comm comm, CacheStorage storage, Backup backup, MonitoringType monitoringType, long maxCapacity) {
        this(name, cluster, comm, storage, backup, createMonitor(monitoringType, name), maxCapacity);
    }

    Cache(String name, Cluster cluster, Comm comm, CacheStorage storage, Backup backup, CacheMonitor monitor, long maxCapacity) {
        super(name, cluster);
        this.comm = comm;
        this.storage = storage;
        this.hasServer = cluster.hasServer();
        this.monitor = monitor;

        this.backup = backup; // new Backup(comm, null);
        this.idAllocator = new IdAllocator(this, (RefAllocator) cluster);

        if (DIRTY_READS)
            this.ownerClocks = new NonBlockingHashMapLong<OwnerClock>();
        else
            this.ownerClocks = null;

        this.monitor.setMonitoredObject(this);
        getCluster().addNodeChangeListener(this);
        this.comm.setReceiver(this);
        this.backup.setCache(this);

        this.owned = new NonBlockingHashMapLong<CacheLine>();
        this.shared = buildSharedCache(maxCapacity);
        this.pendingOps = new NonBlockingHashMapLong<ArrayList<Op>>();
        this.pendingMessages = new NonBlockingHashMapLong<HashSet<LineMessage>>();
    }

    private ConcurrentMap<Long, CacheLine> buildSharedCache(long maxCapacity) {
        return new ConcurrentLinkedHashMap.Builder<Long, CacheLine>().initialCapacity(1000).maximumWeightedCapacity(maxCapacity).weigher(new Weigher<CacheLine>() {

            @Override
            public int weightOf(CacheLine line) {
                return 1 + line.size();
            }

        }).listener(new EvictionListener<Long, CacheLine>() {

            @Override
            public void onEviction(Long id, CacheLine line) {
                evictLine(line, true);
            }

        }).build();
    }

    static CacheMonitor createMonitor(MonitoringType monitoringType, String name) {
        if (monitoringType == null)
            return (CacheMonitor) Proxy.newProxyInstance(Cache.class.getClassLoader(), new Class<?>[]{CacheMonitor.class}, DegenerateInvocationHandler.INSTANCE);
        else
            switch (monitoringType) {
                case JMX:
                    return new JMXCacheMonitor(name);
                case METRICS:
                    return new MetricsCacheMonitor();
            }
        throw new IllegalArgumentException("Unknown MonitoringType " + monitoringType);
    }

    public void setCompareBeforeWrite(boolean value) {
        assertDuringInitialization();
        this.compareBeforeWrite = value;
    }

    @ManagedAttribute
    public boolean isCompareBeforeWrite() {
        return compareBeforeWrite;
    }

    public void setMaxItemSize(int maxItemSize) {
        assertDuringInitialization();
        this.maxItemSize = maxItemSize;
    }

    @ManagedAttribute
    public int getMaxItemSize() {
        return maxItemSize;
    }

    public void setReuseLines(boolean value) {
        assertDuringInitialization();
        this.reuseLines = value;
    }

    @ManagedAttribute
    public boolean isReuseLines() {
        return reuseLines;
    }

    public void setReuseSharerSets(boolean value) {
        assertDuringInitialization();
        this.reuseSharerSets = value;
    }

    @ManagedAttribute
    public boolean isReuseSharerSets() {
        return reuseSharerSets;
    }

    public void setRollbackSupported(boolean value) {
        assertDuringInitialization();
        this.rollbackSupported = value;
    }

    public void setSynchronous(boolean value) {
        assertDuringInitialization();
        this.synchronous = value;
    }

    @ManagedAttribute
    public boolean isRollbackSupported() {
        return rollbackSupported;
    }

    private Checksum getChecksum() {
        assert compareBeforeWrite;
        return new DoubleHasher(); // new MessageDigestChecksum("MD5"); // new MessageDigestChecksum("SHA-1"); // new MessageDigestChecksum("SHA-256"); 
    }

    @Override
    public void init() throws Exception {
        super.init();

        if (synchronous)
            throw new RuntimeException("Synchronous mode has not been implemented yet.");

        this.freeLineList = reuseLines ? new ConcurrentLinkedDeque<CacheLine>() : null;
        this.freeSharerSetList = reuseSharerSets ? new ConcurrentLinkedDeque<TShortHashSet>() : null;
        this.broadcastsRoutedToServer = hasServer && ((AbstractComm) comm).isSendToServerInsteadOfMulticast(); // this is a special case that requires special handling b/c of potential consistency problems (see MainMemory)
    }

    void allocatorReady() {
        LOG.info("Id allocator is ready");
        if(getCluster().isOnline() && getCluster().isMaster())
            setReady(true);
    }
    @Override
    protected void start(boolean master) {
        if(idAllocator.isReady())
            setReady(true);
    }

    @Override
    public void awaitAvailable() throws InterruptedException {
        super.awaitAvailable();
    }

    public void setReceiver(MessageReceiver receiver) {
        assertDuringInitialization();
        this.receiver = receiver;
    }

    public boolean hasServer() {
        return hasServer;
    }

    public void addCacheListener(CacheListener listener) {
        listeners.add(listener);
    }

    public void removeCacheListener(CacheListener listener) {
        listeners.remove(listener);
    }

    Iterator<CacheLine> ownedIterator() {
        return owned.values().iterator();
    }

    //<editor-fold defaultstate="collapsed" desc="Types">
    /////////////////////////// Types ///////////////////////////////////////////
    enum State {

        I, S, O, E; // Order matters! (used by setNextState)

        public boolean isLessThan(State other) {
            return compareTo(other) < 0;
        }

    }

    static class CacheLine {

        private static final byte LOCKED = 1;
        public static final byte MODIFIED = 1 << 1;
        public static final byte SLAVE = 1 << 2; // true when slave(s) think line is owned by us
        public static final byte DELETED = 1 << 3;
        private long id;                // 8
        private byte flags;             // 1
        //private short sem;              // 2
        //long timeAccessed;
        private State state;            // 4
        private State nextState;        // 4
        private long version;           // 8 
        private long ownerClock;        // 8 must contain a counter that is monotonically increasing for each owner, e.g, the message id
        private ByteBuffer data;        // 4
        private short owner = -1;       // 2
        private TShortHashSet sharers;  // 4
        private CacheListener listener; // 4
        // =
        // 47 (+ 8 = 56)

        public long getId() {
            return id;
        }

        private void clearFlags() {
            flags = 0;
        }

        void lock() {
            flags |= LOCKED;//sem++;
        }

        boolean unlock() {
            if (!isLocked())
                throw new IllegalStateException("Item has not been pinned!");
            flags &= ~LOCKED;
            return true;
//            sem--;
//            if (sem < 0)
//                throw new IllegalStateException("Item has been released more time than it's been pinned!");
//            return sem == 0;
        }

        public boolean isLocked() {
            return (flags & LOCKED) != 0; // sem > 0;
        }

        public boolean is(byte flag) {
            return (flags & flag) != 0;
        }

        private void set(byte flag, boolean value) {
            flags = (byte) (value ? (flags | flag) : (flags & ~flag));
        }

        public State getNextState() {
            return nextState;
        }

        public short getOwner() {
            return owner;
        }

        public State getState() {
            return state;
        }

        public long getVersion() {
            return version;
        }

        public long getOwnerClock() {
            return ownerClock;
        }

        public void setOwnerClock(long clock) {
            this.ownerClock = clock;
        }

        public ByteBuffer getData() {
            return data;
        }

        public CacheListener getListener() {
            return listener;
        }

        public void setListener(CacheListener listener) {
            this.listener = listener;
        }

        public int size() {
            return data != null ? data.capacity() : 0; // user's care about capacity, not actual usage
        }

        public void rewind() {
            if (data != null)
                data.rewind();
        }

        @Override
        public String toString() {
            final StringBuffer sb = new StringBuffer();
            sb.append("LINE: ").append(hex(id));
            sb.append(" ").append(state).append(" ");
            if (nextState != null)
                sb.append("(->").append(nextState).append(")");
            sb.append(" OWN: ").append(owner);
            sb.append(" SHARE: ").append(sharers);
            sb.append(" VER: ").append(version);
            sb.append(" DATA: ").append(data != null ? "(" + size() + " bytes)" : "null");
            if (isLocked())
                sb.append(" LOCKED");
            if (is(MODIFIED))
                sb.append(" MODIFIED");
            if (is(SLAVE))
                sb.append(" SLAVE");
            if (is(DELETED))
                sb.append(" DELETED");
            return sb.toString();
        }

    }
    //</editor-fold>

    public boolean isLocked(long id) {
        final CacheLine line = getLine(id);
        if (line == null)
            return false;
        else
            synchronized (line) {
                return line.isLocked();
            }
    }

    State getState(long id) {
        final CacheLine line = getLine(id);
        if (line == null)
            return null;
        else
            synchronized (line) {
                return line.getState();
            }
    }

    //<editor-fold defaultstate="collapsed" desc="Execution flow">
    /////////////////////////// Execution flow ///////////////////////////////////////////
    public Object doOp(Op.Type type, long id, Object data, Object extra, Transaction txn) throws TimeoutException {
        if (!getCluster().isMaster() && type != Op.Type.LSTN)
            throw new IllegalStateException("Node is a slave. Cannot run grid operations");

        if (LOG.isDebugEnabled())
            LOG.debug("Run(fast): Op.{}(line:{}{}{})", new Object[]{type, hex(id), (data != null ? ", data:" + data : ""), (extra != null ? ", extra:" + extra : "")});
        Object result = runFastTrack(id, type, data, extra, txn);
        if (result instanceof Op)
            return doOp((Op) result);
        else if (result == PENDING) {
            if (Thread.currentThread() instanceof CommThread)
                throw new RuntimeException("This operation blocks a comm thread.");
            return doOp(new Op(type, id, data, extra, txn)); // "slow" track
        } else
            return result;
    }

    public ListenableFuture<Object> doOpAsync(Op.Type type, long id, Object data, Object extra, Transaction txn) {
        if (!getCluster().isMaster())
            throw new IllegalStateException("Node is a slave. Cannot run grid operations");

        if (LOG.isDebugEnabled())
            LOG.debug("Run(fast): Op.{}(line:{}{}{})", new Object[]{type, hex(id), (data != null ? ", data:" + data : ""), (extra != null ? ", extra:" + extra : "")});
        Object result = runFastTrack(id, type, data, extra, txn);
        if (result instanceof Op)
            return doOpAsync((Op) result);
        else if (result == PENDING)
            return doOpAsync(new Op(type, id, data, extra, txn)); // "slow" track
        else
            return Futures.immediateFuture(result);
    }

    /**
     * This one blocks!
     *
     * @param op
     * @return
     */
    private Object doOp(Op op) throws TimeoutException {
        try {
            if (op.txn != null)
                op.txn.add(op);
            Object result = runOp(op);
            if (result == PENDING)
                return op.getResult(timeout, TimeUnit.MILLISECONDS);
            else
                return result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new TimeoutException(e);
        } catch (InterruptedException e) {
            return null;
        } catch (ExecutionException e) {
            Throwable ex = e.getCause();
            if (ex instanceof TimeoutException)
                throw (TimeoutException) ex;
            Throwables.propagateIfPossible(ex);
            throw Throwables.propagate(ex);
        }
    }

    private ListenableFuture<Object> doOpAsync(Op op) {
        if (op.txn != null)
            op.txn.add(op);
        Object result = runOp(op);
        if (result == PENDING)
            return op.getFuture();
        else
            return Futures.immediateFuture(result);
    }

    /**
     * We try to run the op w/o creating an Op object.
     */
    private Object runFastTrack(long id, Op.Type type, Object data, Object extra, Transaction txn) {
        if (!type.isOf(FAST_TRACK_OPS))
            return PENDING; // no fast track
        final CacheLine line = getLine(id);
        if (line == null) {
            final Object res = handleOpNoLine(type, id, extra);
            if (res != DIDNT_HANDLE)
                return res;
            return PENDING; // no fast track
        }

        Object res;
        synchronized (line) {
            res = handleOp(line, type, data, extra, txn, false, LINE_EVERYTHING_CHANGED);
        }
        if (res != PENDING)
            monitor.addOp(type, 0);
        return res;
    }

    // visible for testing
    Object runOp(Op op) {
        LOG.debug("Run: {}", op);
        recursive.set(Boolean.TRUE);
        try {
            if (op.type == Op.Type.PUT || op.type == Op.Type.ALLOC)
                return execOp(op, null);

            final long id = op.line;
            CacheLine line = getLine(id);

            if (line == null) {
                Object res = handleOpNoLine(op.type, op.line, op.getExtra());
                if (res != DIDNT_HANDLE)
                    return res;
                else
                    line = (CacheLine) createNewCacheLine(op);
            }

            final Object res;
            synchronized (line) {
                res = execOp(op, line);
            }

            receiveShortCircuit();

            if (res instanceof Op)
                return runOp((Op) res);
            return res;
        } finally {
            recursive.remove();
        }
    }

    private Object execOp(Op op, CacheLine line) {
        Object res;
        try {
            res = handleOp(line, op, false, LINE_EVERYTHING_CHANGED);
        } catch (Throwable t) {
            if (op.hasFuture()) {
                op.setException(t);
                return null;
            } else {
                return Throwables.propagate(t);
            }
        }
        if (res == PENDING) {
            op.setStartTime(System.nanoTime());
            LOG.debug("Adding op to pending {} on line {}", op, line);
            addPendingOp(line, op);
        }
        return res;
    }

    /**
     * @param pending true if this op is already pending
     */
    private Object handleOp(CacheLine line, Op op, boolean pending, int lineChange) {
        LOG.debug("handleOp: {} line: {}", op, line);
        try {
            Object res;
            switch (op.type) {
                case PUT:
                    res = handleOpPut(op, line);
                    break;
                case ALLOC:
                    res = handleOpAlloc(op, line);
                    break;
                default:
                    res = handleOp(line, op.type, op.data, op.getExtra(), op.txn, pending, lineChange);
                    break;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("handleOp: {} -> {} line: {}", new Object[]{op, res, line});
            if (res == PENDING)
                return res;
            else if (res instanceof Op)
                return res;
            else {// res != PENDING
                completeOp(line, op, res, pending);
                return res;
            }
        } catch (Exception e) {
            opException(line, op, e, pending);
            return null;
        }
    }

    private Object handleOp(CacheLine line, Op.Type type, Object data, Object extra, Transaction txn, boolean pending, int lineChange) {
        assert line != null || type == Op.Type.PUT || type == Op.Type.ALLOC;
        accessLine(line);

        handleNodeEvents(line);

        Object res = null;

        if (line != null && shouldHoldOp(line, type))
            res = PENDING;
        else {
            switch (type) {
                case GET:
                case GETS:
                    res = handleOpGet(line, type, data, nodeHint(extra), txn, lineChange);
                    break;
                case GETX:
                    res = handleOpGetX(line, data, nodeHint(extra), txn, lineChange);
                    break;
                case GET_FROM_OWNER:
                    res = handleOpGetFromOwner(line, extra);
                    break;
                case SET:
                    res = handleOpSet(line, data, nodeHint(extra), txn, lineChange);
                    break;
                case DEL:
                    res = handleOpDel(line, nodeHint(extra), txn, lineChange);
                    break;
                case SEND:
                    res = handleOpSend(line, extra, lineChange);
                    break;
                case PUSH:
                    res = handleOpPush(line, extra, lineChange);
                    break;
                case PUSHX:
                    res = handleOpPushX(line, extra, lineChange);
                    break;
                case LSTN:
                    res = handleOpListen(line, extra);
                    break;
            }
        }

        if (!pending && type.isOf(HIT_OR_MISS_OPS)) {
            if (res != PENDING) {
                if (line.getState() == State.I)
                    monitor.addStaleHit();
                else
                    monitor.addHit();
            }
            // addMiss and addInvalidates are handled by setNextState();
        }

        return res;
    }

    private void completeOp(CacheLine line, Op op, Object res, boolean pending) {
        long duration = 0;
        if (pending) {
            assert op.getStartTime() != 0;
            duration = (System.nanoTime() - op.getStartTime()) / 1000; // Microseconds
        }
        if (op.hasFuture())
            op.setResult(res);
        monitor.addOp(op.type, duration);
    }

    private void opException(CacheLine line, Op op, Throwable t, boolean pending) {
        if (pending) {
            if (!op.hasFuture())
                op.createFuture();

            op.setException(t);
        } else
            throw Throwables.propagate(t);
    }

    /**
     * Called for a LineMessage when the line is not found
     *
     * @return true if the op was handled - nothing else necessary. false if not, and creating the line is required
     */
    protected Object handleOpNoLine(Op.Type type, long id, Object extra) {
        if (LOG.isDebugEnabled())
            LOG.debug("Line {} not found.", hex(id));
        switch (type) {
            case GET_FROM_OWNER:
                return extra;
            case PUSH:
            case PUSHX:
                LOG.info("Attempt to push line {}, but line is not in cache. ", hex(id));
                return null;
            default:
                return DIDNT_HANDLE;
        }
    }

    private boolean shouldHoldOp(CacheLine line, Op.Type op) {
        return (hasPendingMessages(line) // give mesages a chance if we're not part of a transaction (line isn't locked) and messages are simply waiting for backups or another independent sets (that isn't part of a transaction)
                && op.isOf(LOCKING_OPS)
                && !line.isLocked()
                && !(line.getState() != State.E && line.getNextState() == State.E))
                || (line.is(CacheLine.MODIFIED) && op.isOf(PUSH_OPS));
    }

    private void handlePendingOps(CacheLine line, int change) {
        if (line == null)
            return;
        for (Iterator<Op> it = getPendingOps(line).iterator(); it.hasNext();) {
            final Op op = it.next();
            if (LOG.isDebugEnabled())
                LOG.debug("Handling pending op {}, change = {}", op, change);
            if (handleOp(line, op, true, change) != PENDING)
                it.remove();
        }
    }

    @Override
    public void receive(Message message) {
        if (recursive.get() != Boolean.TRUE) {
            recursive.set(Boolean.TRUE);
            try {
                LOG.debug("Received: {}", message);
                receive1(message);
                receiveShortCircuit();
            } finally {
                recursive.remove();
            }
        } else { // short-circuit
            LOG.debug("Received short-circuit: {}", message);
            Queue<Message> ms = shortCircuitMessage.get();
            if (ms == null) {
                ms = new ArrayDeque<Message>();
                shortCircuitMessage.set(ms);
            }
            ms.add(message);
        }
    }

    private void receive1(Message message) {
        switch (message.getType()) {
            case MSG:
                handleMessageMsg((Message.MSG) message);
                return;
            case MSGACK:
                if (((LineMessage) message).getLine() == -1) {
                    if (receiver != null)
                        receiver.receive(message);
                    return;
                }
                break;
            case BACKUP_PACKETACK:
                backup.receive(message);
                return;
            default:
        }
        runMessage((LineMessage) message);
        monitor.addMessageReceived(message.getType());
    }

    private void runMessage(LineMessage message) {
        final long id = message.getLine();
        CacheLine line = getLine(id);
        if (line == null) {
            if (handleMessageNoLine(message))
                return;
            else
                line = (CacheLine) createNewCacheLine(message);
        }

        synchronized (line) {
            handleMessage(message, line);
        }
    }

    /**
     * Special handling for msg.
     */
    private void handleMessageMsg(Message.MSG message) {
        if (receiver == null)
            return;

        setOwnerClockPut(message);

        if (message.getLine() == -1) {
            receiver.receive(message);
            if (message.isReplyRequired())
                send(Message.MSGACK(message));
            return;
        }

        CacheLine line = getLine(message.getLine());
        if (line == null) {
            boolean res = handleMessageNoLine(message);
            assert res;
            return;
        }
        synchronized (line) {
            if (handleNotOwner(message, line))
                return;
        }

        receiver.receive(message);
        if (message.isReplyRequired())
            send(Message.MSGACK(message));
    }

    private void handleMessage(LineMessage message, CacheLine line) {
        assert line != null;
        handleNodeEvents(line);
        int change = handleMessage1(message, line);
        handlePendingOps(line, change);
        handlePendingMessagesAfterMessage(line, change);
    }

    private int handleMessage1(LineMessage message, CacheLine line) {
        accessLine(line);

        if (shouldHoldMessage(line, message)) {
            LOG.debug("Adding message to pending {} on line {}", message, line);
            addPendingMessage(line, message);
            if (line.is(CacheLine.MODIFIED))
                backup.flush();
            return LINE_NO_CHANGE;
        }

        try {
            switch (message.getType()) {
                case PUT:
                    return handleMessagePut((Message.PUT) message, line);
                case PUTX:
                    return handleMessagePutX((Message.PUTX) message, line);
                case GET:
                    return handleMessageGet((Message.GET) message, line);
                case GETX:
                    return handleMessageGetX((Message.GET) message, line);
                case INV:
                    return handleMessageInvalidate((Message.INV) message, line);
                case INVACK:
                    return handleMessageInvalidateAck(message, line);
                case NOT_FOUND:
                    return handleMessageNotFound(message, line);
                case CHNGD_OWNR:
                    return handleMessageChngdOwnr((Message.CHNGD_OWNR) message, line);
                case MSGACK:
                    return handleMessageMsgAck(message, line);
                case BACKUP: // in slave mode only
                    return handleMessageBackup((Message.PUT) message, line);
                case BACKUPACK:
                    return handleMessageBackupAck((Message.BACKUPACK) message, line);
                case TIMEOUT:
                    return handleMessageTimeout(message, line);
                default:
                    LOG.warn("Unhandled message {}", message);
                    return LINE_NO_CHANGE;
            }
        } catch (IrrelevantStateException e) {
            LOG.warn("Got message {} when at irrelevant state {}", message, line.state);
            return LINE_NO_CHANGE;
        }
    }

    /**
     * Called for a LineMessage when the line is not found
     *
     * @param message
     * @return true if the message was handled - nothing else necessary. false if not, and creating the line is required
     */
    protected boolean handleMessageNoLine(LineMessage message) {
        if (LOG.isDebugEnabled())
            LOG.debug("Line {} not found.", hex(message.getLine()));
        switch (message.getType()) {
            case INV:
                send(Message.INVACK((Message.INV) message));
                return true;
            //case CHNGD_OWNR:
            case INVACK:
                return true;
            case GET:
            case GETX:
            case MSG:
                handleNotOwner(message, null);
                return true;
            default:
                return false;
        }
    }

    private boolean handleNotOwner(LineMessage msg, CacheLine line) {
        if (line != null && line.is(CacheLine.DELETED)) {
            send(Message.NOT_FOUND(msg));
            return true;
        }
        if (line == null || line.state == State.I || line.state == State.S) {
            final long id;
            final short owner;
            final boolean certain;
            if (line == null) {
                id = msg.getLine();
                owner = (short) -1;
                certain = false;
            } else {
                id = line.getId();
                owner = line.getOwner();
                // actually, S doesn't mean we're certain about the owner b/c transfer of ownership (PUTX) is done before sending INVs. 
                // However, we're more certain than when we're I. 
                // It doesn't matter, though, since at the moment the certain field is ignored.
                certain = (line.state == State.S);
            }

            if (certain || !msg.isBroadcast())
                send(Message.CHNGD_OWNR(msg, id, owner, certain));
            else if (msg.isBroadcast())
                send(Message.ACK(msg));

            return true;
        } else
            return false;
    }

    private int handlePendingMessages(CacheLine line, CacheMonitor.MessageDelayReason reason) {
        int change = LINE_NO_CHANGE;

        final long now = System.nanoTime();
        int messageCount = 0;
        long totalDelay = 0;

        for (LineMessage msg : getAndClearPendingMessages(line)) {
            LOG.debug("Handling pending message {}", msg);
            change |= handleMessage1(msg, line);

            messageCount++;
            totalDelay += now - msg.getTimestamp();
        }

        if (messageCount > 0)
            monitor.addMessageHandlingDelay(messageCount, totalDelay, reason);

        if (change != LINE_NO_CHANGE) {
            handlePendingOps(line, change);
            handlePendingMessagesAfterMessage(line, change);
        }
        return change;
    }

    private static final long MESSAGES_BLOCKED_BY_LOCK = Enums.setOf(Message.Type.GET, Message.Type.GETX, Message.Type.INV, Message.Type.PUT, Message.Type.PUTX);

    private boolean shouldHoldMessage(CacheLine line, Message message) {
        final boolean res = message.getType().isOf(MESSAGES_BLOCKED_BY_LOCK)
                && (line.isLocked() || line.is(CacheLine.MODIFIED) || (line.getState() != State.E && line.getNextState() == State.E));
        if (res && message.getType() == Message.Type.INV && !line.isLocked() && !line.is(CacheLine.MODIFIED)) // INV isn't locked by -> E
            return false;
        return res;
    }

    private void handlePendingMessagesAfterMessage(CacheLine line, int change) {
        if (!line.isLocked() && !line.is(CacheLine.MODIFIED)) {
            CacheMonitor.MessageDelayReason reason = null;
            if ((change & LINE_MODIFIED_CHANGED) != 0)
                reason = CacheMonitor.MessageDelayReason.BACKUP;
            else if ((change & LINE_STATE_CHANGED) != 0)
                reason = CacheMonitor.MessageDelayReason.OTHER;

            if (reason != null)
                handlePendingMessages(line, reason);
        }
    }

    public void send(Message.MSG message) {
        send((Message) message);
        monitor.addOp(Op.Type.SEND, 0);
    }
    //</editor-fold>

    //<editor-fold defaultstate="expanded" desc="Logic">
    /////////////////////////// Logic ///////////////////////////////////////////
    //<editor-fold defaultstate="expanded" desc="Transactions">
    /////////////////////////// Transactions ///////////////////////////////////////////
    public Transaction beginTransaction() {
        return new Transaction(rollbackSupported);
    }

    public void rollback(Transaction txn) {
        if (!rollbackSupported)
            throw new IllegalStateException("Cache nconfigured to not support rollbacks");

        txn.forEachRollback(new TLongObjectProcedure<RollbackInfo>() {

            @Override
            public boolean execute(long id, RollbackInfo r) {
                final CacheLine line = getLine(id);
                synchronized (line) {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Rolling back line {} to version {}. Modified = {}", new Object[]{hex(line.getId()), r.version, r.modified});
                    line.version = r.version;
                    line.set(CacheLine.MODIFIED, r.modified);
                    writeData(line, r.data);
                    return true;
                }
            }

        });
    }

    public void endTransaction(Transaction txn, boolean abort) throws InterruptedException {
        Throwable ex = null;
        for (Op op : txn.getOps()) {
            try {
                if (op.hasFuture())
                    op.getResult();
            } catch (Throwable e) {
                LOG.debug("Error in op: " + op, e);
                if (ex == null)
                    ex = e;
            }
        }

        boolean flush = false;

        final ArrayList<CacheLine> unmodified = new ArrayList<CacheLine>();
        backup.startBackup();
        try {
            for (TLongIterator it = txn.getLines().iterator(); it.hasNext();) {
                final long id = it.next();
                final CacheLine line = getLine(id);
                synchronized (line) {
                    if (unlockLine(line, txn)) {
                        if (!line.is(CacheLine.MODIFIED))
                            unmodified.add(line);
                        else {
                            line.set(CacheLine.SLAVE, true);
                            backup.backup(line.getId(), line.getVersion());
                            if (hasPendingMessages(line))
                                flush = true;
                        }
                    }
                }
            }
        } finally {
            backup.endBackup();
        }
        if (flush)
            backup.flush();

        for (CacheLine line : unmodified) {
            synchronized (line) {
                handlePendingMessages(line, CacheMonitor.MessageDelayReason.LOCK);
            }
        }

        if (!abort) {
            if (ex != null) {
                if (ex instanceof ExecutionException) {
                    ex = ex.getCause();
                }
                Throwables.propagateIfPossible(ex);
                throw Throwables.propagate(ex);
            }
        }
    }

    public void release(long id) {
        final CacheLine line = getLine(id);
        synchronized (line) {
            if (unlockLine(line, null)) {
                if (!line.is(CacheLine.MODIFIED))
                    handlePendingMessages(line, CacheMonitor.MessageDelayReason.LOCK);
                else
                    backupLine(line);
            }
        }
    }

    private void backupLine(CacheLine line) {
        line.set(CacheLine.SLAVE, true);
        backup.startBackup();
        backup.backup(line.getId(), line.getVersion());
        backup.endBackup();
        if (hasPendingMessages(line))
            backup.flush();
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Op handling">
    /////////////////////////// Op handling ///////////////////////////////////////////
    private Object handleOpGet(CacheLine line, Op.Type type, Object data, short nodeHint, Transaction txn, int change) {
        if ((change & (LINE_STATE_CHANGED | LINE_OWNER_CHANGED | (synchronous ? LINE_MODIFIED_CHANGED : 0))) == 0)
            return PENDING;

        if (line.is(CacheLine.DELETED))
            handleDeleted(line);

        if (!transitionToS(line, nodeHint)) {
            if (type != Op.Type.GETS && line.version > 0 && !isPossibleInconsistencies(line)) {
                if (data != null) {
                    readData(line, (Persistable) data);
                    return null;
                } else
                    return readData(line);
            } else
                return PENDING;
        }

        if (type == Op.Type.GETS)
            lockLine(line, txn);

        if (data != null) {
            readData(line, (Persistable) data);
            return null;
        } else
            return readData(line);
    }

    private Object handleOpGetX(CacheLine line, Object data, short nodeHint, Transaction txn, int change) {
        if ((change & (LINE_STATE_CHANGED | LINE_OWNER_CHANGED)) == 0)
            return PENDING;

        if (line.is(CacheLine.DELETED))
            handleDeleted(line);

        if (!transitionToE(line, nodeHint))
            return PENDING;

        lockLine(line, txn); // we get here when were O (see transitionToE or E). 

        if (data != null) {
            readData(line, (Persistable) data);
            return null;
        } else
            return readData(line);
    }

    private Object handleOpGetFromOwner(CacheLine line, Object extra) {
        final Op get = (Op) extra;
        final short owner = line.getOwner();
        if (owner >= 0)
            get.setExtra(owner);
        return get;
    }

    private boolean transitionToS(CacheLine line, short nodeHint) {
        if (line.state.isLessThan(State.S)) {
            if (setNextState(line, State.S))
                send(Message.GET(getTarget(line, nodeHint), line.id));
            return false;
        } else
            return true;
    }

    private boolean transitionToO(CacheLine line, short nodeHint) {
        if (line.state.isLessThan(State.O)) {
            if (setNextState(line, State.O))
                send(Message.GETX(getTarget(line, nodeHint), line.id));
            return false;
        } else
            return true;
    }

    private boolean transitionToE(CacheLine line, short nodeHint) {
        if (!transitionToO(line, nodeHint))
            return false;
        assert !line.state.isLessThan(State.O);

        final boolean res;

        if (line.state.isLessThan(State.E)) {
            if (setNextState(line, State.E)) {
                assert !line.sharers.isEmpty();
                for (TShortIterator it = line.sharers.iterator(); it.hasNext();) {
                    final short sharer = it.next();
                    if (sharer != Comm.SERVER) // we've already INVed server in handleMessagePutX
                        send(Message.INV(sharer, line.getId(), line.getOwner())); // owner may not be us but the previous owner - see handleMessagePutX
                }
            }
            if (broadcastsRoutedToServer)
                res = !line.sharers.contains(Comm.SERVER); // in this particular case, we wait for server to INVACK (this case may have consistency problems, otherwise)
            else if (!hasServer)
                res = !line.sharers.contains(line.getOwner()); // getOwner still has the old owner. when it invacks, it means it has inved its slaves so we're safe.
            else
                res = true; // we don't wait for acks, but GET messages are kept pending until the transition
        } else
            res = true;
        // INVACKs cannot cause deadlocks (really? proof?), so we don't need to wait and see if they timeout.

        if (res)
            line.set(CacheLine.MODIFIED, true); // let slaves know we own the line
        return res;
    }

    private Object handleOpSet(CacheLine line, Object data, short nodeHint, Transaction txn, int change) {
        if ((change & (LINE_STATE_CHANGED | LINE_OWNER_CHANGED)) == 0)
            return PENDING;

        if (line.is(CacheLine.DELETED))
            handleDeleted(line);

        if (!transitionToE(line, nodeHint))
            return PENDING;

        setData(line, data, txn);

        if (txn == null && !line.isLocked())
            backupLine(line);

        return null;
    }

    private void handleDeleted(CacheLine line) {
        if (isReserved(line.getId())) {
            line.set(CacheLine.DELETED, false);
            setState(line, State.E);
        } else
            throw new RefNotFoundException(line.getId());
    }

    private Object handleOpPut(Op op, CacheLine line) {
        assert line == null;

        long id = idAllocator.allocateIds(op, 1);
        if (id == -1)
            return PENDING;

        line = allocateCacheLine();
        line.id = id;
        setState(line, State.E);
        setOwner(line, myNodeId());
        setData(line, op.data, op.txn);

        lockLine(line, op.txn);

        putLine(id, line, 0, line.size()); // we put this last so that no one can touch this line while it's being built.
        return id;
    }

    private Object handleOpAlloc(Op op, CacheLine line) {
        assert line == null;

        final int count = (Integer) op.getExtra();
        long id = idAllocator.allocateIds(op, count);
        if (id == -1)
            return PENDING;

        for (int i = 0; i < count; i++) {
            line = allocateCacheLine();
            line.id = id + i;
            setState(line, State.E);
            setOwner(line, myNodeId());
            setData(line, null, op.txn);
            lockLine(line, op.txn);

            putLine(id + i, line, 0, line.size()); // we put this last so that even in the locking cache no one can touch this line while it's being built.
        }
        return id;
    }

    private Object handleOpDel(CacheLine line, short nodeHint, Transaction txn, int change) {
        if ((change & (LINE_STATE_CHANGED | LINE_OWNER_CHANGED)) == 0)
            return PENDING;

        if (!transitionToE(line, nodeHint))
            return PENDING;

        final long id = line.getId();

        line.set(CacheLine.DELETED, true);

        if (hasServer()) {
            if (line.state == State.E)
                setState(line, State.O);
            line.sharers.add(Comm.SERVER);
            send(Message.DEL(Comm.SERVER, id));
        } else
            setState(line, State.I);

        deallocateStorage(id, line.data);

        fireLineEvicted(line);
        return null;
    }

    private Object handleOpSend(CacheLine line, Object extra, int change) {
        if (line.is(CacheLine.DELETED))
            handleDeleted(line);

        if ((change & LINE_OWNER_CHANGED) == 0)
            return PENDING; // there's no reason to resend

        final Message.MSG msg = (Message.MSG) extra;
        if (msg.getNode() != -1 && msg.getNode() == line.getOwner())
            return PENDING; // there's no reason to resend

        if (!line.getState().isLessThan(State.O)) {
            msg.setNode(myNodeId());
            msg.setReplyRequired(false);
            msg.setIncoming(); // must be done last! (this is a special trick. in the normal case setAckRequired must never be done on incoming messages so we assert it - to bypass in this case, this must come last)
            receive(msg);
            return null;
        }

        // we make a copy of the message because it may have been sent and sits in some comm queues,
        // so changing the target node might cause trouble.
        final Message.MSG msg1 = Message.MSG(line.getOwner(), msg.getLine(), msg.getData());
        send((Message) msg1);
        return PENDING; // unlike other ops, this one always returns pending, and is completed by handleMessageMsgAck
    }

    private Object handleOpPush(CacheLine line, Object extra, int change) {
        if ((change & LINE_MODIFIED_CHANGED) == 0) {
            assert line.is(CacheLine.MODIFIED);
            return PENDING;
        }

        if (line.getState().isLessThan(State.O)) {
            LOG.info("Attempt to push line {} while state is only {}", hex(line.getId()), line.getState());
            return null; // throw new IllegalStateException("Line " + line.getId() + " is not owned by this cache.");
        }

        setState(line, State.O);
        short[] toNodes = (short[]) extra;
        line.sharers.addAll(toNodes);

        for (short node : toNodes) {
            send(Message.PUT(node, line.id, line.version, readOnly(line.data)));
            line.rewind();
        }
        return null;
    }

    private Object handleOpPushX(CacheLine line, Object extra, int change) {
        if ((change & LINE_MODIFIED_CHANGED) == 0) {
            assert line.is(CacheLine.MODIFIED);
            return PENDING;
        }

        if (line.getState().isLessThan(State.E)) {
            LOG.info("Attempt to push line {} while state is only {}", line.getId(), line.getState());
            return null; // throw new IllegalStateException("Line " + line.getId() + " is not owned exclusively by this cache.");
        }

        short toNode = (Short) extra;
        setOwner(line, toNode);
        final short[] sharers = line.sharers.toArray();
        // TODO: maybe S, or, rather, transitional O. We could add this node to sharers and  if new owner dies, we become owner here and in the server
        setState(line, State.I);

        send(Message.PUTX(toNode, line.id, sharers, line.version, readOnly(line.data)));
        line.rewind();
        return null;
    }

    private Void handleOpListen(CacheLine line, Object listener) {
        line.setListener((CacheListener) listener);
        return null;
    }

    private static short nodeHint(Object obj) {
        return obj != null ? (Short) obj : -1;
    }

    private static short getTarget(CacheLine line, short nodeHint) {
        short target = line.getOwner();
        if (target < 0)
            target = nodeHint;
        return target;
    }

    private void setData(CacheLine line, Object data, Transaction txn) {
        assert !line.state.isLessThan(State.O);

        if (txn != null && rollbackSupported && !txn.isRecorded(line.getId()))
            txn.recordRollback(line.getId(), line.getVersion(), line.is(CacheLine.MODIFIED), line.getData() != null ? Persistables.toByteArray(line.getData()) : null);
        if (writeData(line, data) || line.version == 0) { // first write always updates version, even if it's a null.
            line.version++;
            line.set(CacheLine.MODIFIED, true);
            if (LOG.isDebugEnabled())
                LOG.debug("Line {} now has a new version {}. Setting to modified.", hex(line.getId()), line.getVersion());
        }
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Message handling">
    /////////////////////////// Message handling ///////////////////////////////////////////
    private int handleMessageGet(Message.GET msg, CacheLine line) throws IrrelevantStateException {
        if (handleNotOwner(msg, line))
            return LINE_NO_CHANGE;
        relevantStates(line, State.E, State.O);

        int change = LINE_NO_CHANGE;
        change |= setState(line, State.O) ? LINE_STATE_CHANGED : 0;
        line.sharers.add(msg.getNode());

        send(Message.PUT(msg, line.id, line.version, readOnly(line.data)));
        line.rewind();
        return change;
    }

    private int handleMessagePut(Message.PUT msg, CacheLine line) throws IrrelevantStateException {
        relevantStates(line, State.I, State.S);

        if (line.version > msg.getVersion())
            return LINE_NO_CHANGE;

        int change = LINE_NO_CHANGE;
        change |= setState(line, State.S) ? LINE_STATE_CHANGED : 0;
        change |= setOwner(line, msg.getNode()) ? LINE_OWNER_CHANGED : 0;
        line.version = msg.getVersion();
        writeData(line, msg.getData());
        setOwnerClock(line, msg);

        fireLineReceived(line);
        return change;
    }

    private int handleMessageGetX(Message.GET msg, CacheLine line) throws IrrelevantStateException {
        if (handleNotOwner(msg, line))
            return 0;
        relevantStates(line, State.E, State.O);

        if (line.is(CacheLine.SLAVE)) {
            if (backup.inv(line.getId(), msg.getNode()))
                line.set(CacheLine.SLAVE, false);
        }
        
        if (!hasServer && line.is(CacheLine.SLAVE))
            line.sharers.add(myNodeId());

        final short[] sharers = line.sharers.toArray(); // setState will nullify sharers

        int change = 0;
        // TODO: maybe S, or, rather, transitional O. We could add this node to sharers and  if new owner dies, we become owner here and in the server
        change |= setState(line, (hasServer | !line.is(CacheLine.SLAVE)) ? State.I : State.S) ? LINE_STATE_CHANGED : 0;
        change |= setOwner(line, msg.getNode()) ? LINE_OWNER_CHANGED : 0;

        send(Message.PUTX(msg, line.id, sharers, line.version, readOnly(line.data)));
        line.rewind();

        return change;
    }

    private int handleMessagePutX(Message.PUTX msg, CacheLine line) throws IrrelevantStateException {
        relevantStates(line, State.I, State.S);
        if (line.version > msg.getVersion()) {
            LOG.warn("Got PUTX with version {} which is older than current version {}", msg.getVersion(), line.version);
            return LINE_NO_CHANGE;
        }

        final TShortHashSet sharers = new TShortHashSet((msg.getSharers() != null ? msg.getSharers().length : 0) + 1);
        if (msg.getSharers() != null)
            sharers.addAll(msg.getSharers());
        if (hasServer && msg.getNode() != Comm.SERVER)
            sharers.add(Comm.SERVER); // this is so we make sure the server was notified for the ownership transfer. this is done by INV
        sharers.remove(myNodeId()); // don't INV myself

        int change = LINE_NO_CHANGE;
        change |= line.getState().isLessThan(State.O) ? LINE_OWNER_CHANGED : 0;
        change |= setState(line, sharers.isEmpty() ? State.E : State.O) ? LINE_STATE_CHANGED : 0;
        if (sharers.isEmpty())
            change |= setOwner(line, myNodeId()) ? LINE_OWNER_CHANGED : 0;
        else
            setOwner(line, msg.getNode()); // We set owner to the PREVIOUS owner - used// change |= setOwner(line, cluster.getMyNodeId()) ? LINE_OWNER_CHANGED : 0;
        line.sharers.addAll(sharers);
        line.version = msg.getVersion();
        writeData(line, (Object) msg.getData());

        setOwnerClock(line, msg);

        fireLineReceived(line);

        if (hasServer && msg.getNode() != Comm.SERVER)
            send(Message.INV(Comm.SERVER, line.id, msg.getNode()));
        return change;
    }

    private int handleMessageInvalidate(Message.INV msg, CacheLine line) throws IrrelevantStateException {
        if (getCluster().isMaster())
            relevantStates(line, State.S, State.I, State.O);
        else
            relevantStates(line, State.I, State.E);

        assert line.getState().isLessThan(State.O) || msg.getNode() == Comm.SERVER || !getCluster().isMaster(); // We may get an INV from server when O if line has been transferred to another node as a result of some cluster failure

        final short owner = ((msg.getNode() == Comm.SERVER || msg.getNode() == getCluster().getMyNodeId()) ? msg.getPreviousOwner() : msg.getNode());
        int change = LINE_NO_CHANGE;
        setNextState(line, null);
        change |= setState(line, State.I) ? LINE_STATE_CHANGED : 0;
        change |= setOwner(line, owner) ? LINE_OWNER_CHANGED : 0;
        // If we have a nextState? (i.e. pending ops)? - we do nothing. when the owner unlocks the line it will respond.
        setOwnerClock(line, msg);

        if (getCluster().isMaster()) {
            if (line.is(CacheLine.SLAVE)) {
                if (backup.inv(line.getId(), owner))
                    line.set(CacheLine.SLAVE, false);
            }

            if (line.is(CacheLine.SLAVE))
                addPendingMessage(line, msg);
            else if (msg.getNode() != Comm.SERVER)
                send(Message.INVACK(msg));
        }
        return change;
    }

    private int handleMessageInvalidateAck(LineMessage msg, CacheLine line) throws IrrelevantStateException {
        // invack from slaves
        if (msg.getNode() == myNodeId()) {
            assert line.is(CacheLine.SLAVE);
            if (line.isLocked()) {
                addPendingMessage(line, msg);
                return LINE_NO_CHANGE;
            }

            relevantStates(line, State.I, State.S);

            line.set(CacheLine.SLAVE, false);
            int change = LINE_MODIFIED_CHANGED;
            if (line.getState() == State.S) { // we assume the owner would want us to INV
                setNextState(line, null);
                change |= setState(line, State.I) ? LINE_STATE_CHANGED : 0;
                setOwnerClock(line, msg);
                send(Message.INVACK(line.getOwner(), line.getId()));
            }
            return change;
        }

        // invack from peer
        relevantStates(line, State.O);
        int change = LINE_NO_CHANGE;
        line.sharers.remove(msg.getNode());
        if (line.sharers.isEmpty()) {
            change |= setState(line, line.is(CacheLine.DELETED) ? State.I : State.E) ? LINE_STATE_CHANGED : 0;
            change |= setOwner(line, myNodeId()) ? LINE_OWNER_CHANGED : 0;
            change |= LINE_STATE_CHANGED;
        } else if ((broadcastsRoutedToServer && msg.getNode() == Comm.SERVER)
                || (!hasServer && msg.getNode() == line.getOwner())) {
            change |= LINE_STATE_CHANGED;
        }
        if (!msg.isResponse())
            send(Message.ACK(msg));

        return change;
    }

    private int handleMessageNotFound(LineMessage msg, CacheLine line) throws IrrelevantStateException {
        relevantStates(line, State.I);

        if (msg.getNode() == Comm.SERVER || !hasServer) {
            line.set(CacheLine.DELETED, true);
            return LINE_STATE_CHANGED;
        } else {
            setOwner(line, Comm.SERVER);
            setNextState(line, null);
            return LINE_OWNER_CHANGED;
        }
    }

    private int handleMessageChngdOwnr(Message.CHNGD_OWNR msg, CacheLine line) throws IrrelevantStateException {
        relevantStates(line, State.I, State.S); // S doesn't mean we're certain about the owner b/c transfer of ownership (PUTX) is done before sending INVs. 

        if (msg.getNewOwner() != -1 && getCluster().getMaster(msg.getNewOwner()) == null) {
            // either the node that sent the message has not received a node removal event for the new owner
            // or that we have not received a node addition event.
            if (LOG.isDebugEnabled())
                LOG.debug("Not changing owner of {} to {} because node is not in the cluster.", hex(line.getId()), msg.getNewOwner());
            setNextState(line, null);
            return LINE_OWNER_CHANGED; // ... but we're re-trying the op. hopefully, the cluster info will be in sync.
        }

        if (setOwner(line, msg.getNewOwner())) {
            int change = LINE_OWNER_CHANGED;

            if (msg.getNode() == Comm.SERVER && msg.getNewOwner() == myNodeId()) {
                setState(line, State.E); // it's me! probably we sent PUTX to a node that died. 
                change |= LINE_STATE_CHANGED;
            }

            // Force resending of messages by ops
            // This can also be solved by tracking the target of the message in the Op (this would require passing the old owner to the transitionToX() methods), but I opted for the simpler solution for now.
            setNextState(line, null);
            return change;
        }
        return LINE_NO_CHANGE;
    }

    private int handleMessageMsgAck(LineMessage ack, CacheLine line) throws IrrelevantStateException {
        Op sendOp = null;
        final Collection<Op> pending = getPendingOps(line);
        for (Iterator<Op> it = pending.iterator(); it.hasNext();) {
            final Op op = it.next();
            if (op.type == Op.Type.SEND) {
                Message.MSG msg = (Message.MSG) op.getExtra();
                if (msg.getMessageId() == ack.getMessageId()) {
                    sendOp = op;
                    break;
                }
            }
        }
        if (sendOp != null) {
            completeOp(line, sendOp, null, true);
            removePendingOp(line, sendOp);
        }
        return LINE_NO_CHANGE;
    }

    private int handleMessageTimeout(LineMessage msg, CacheLine line) throws IrrelevantStateException {
        for (Iterator<Op> it = getPendingOps(line).iterator(); it.hasNext();) {
            final Op op = it.next();
            if (!op.hasFuture())
                op.createFuture();
            LOG.info("TIMEOUT: {}", op);
            op.setException(new TimeoutException());
            it.remove();
        }
        line.nextState = null;
        // TODO: push? send to owner?
        return LINE_STATE_CHANGED;
    }

    private int handleMessageBackup(Message.PUT msg, CacheLine line) throws IrrelevantStateException {
        if (getCluster().isMaster()) {
            LOG.warn("Received backup message while master (ignoring): {}", msg);
            return LINE_NO_CHANGE;
        }

        assert !getCluster().isMaster();
        assert msg.getNode() == myNodeId();

        if (line.version > msg.getVersion())
            return LINE_NO_CHANGE;

        // state is set to E. When the master dies, processLineOnNodeEvent in other peers will set S -> I,
        // so we don't need to track sharers.

        int change = LINE_NO_CHANGE;
        change |= setState(line, State.E) ? LINE_STATE_CHANGED : 0;
        change |= setOwner(line, msg.getNode()) ? LINE_OWNER_CHANGED : 0;
        line.version = msg.getVersion();
        writeData(line, msg.getData());

        fireLineReceived(line);
        return change;
    }

    private int handleMessageBackupAck(Message.BACKUPACK msg, CacheLine line) throws IrrelevantStateException {
        relevantStates(line, State.O, State.E);
        assert line.is(CacheLine.MODIFIED);

        assert line.getVersion() >= msg.getVersion();

        int change = LINE_NO_CHANGE;
        if (line.is(CacheLine.MODIFIED) && line.getVersion() == msg.getVersion()) {
            if (LOG.isDebugEnabled())
                LOG.debug("Backup of line {} version {} done. Setting to unmodified.", hex(line.getId()), line.getVersion());
            line.set(CacheLine.MODIFIED, false);
            change |= LINE_MODIFIED_CHANGED;
        }
        return change;
    }
    //</editor-fold>
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Dirty Reads">
    /////////////////////////// Dirty Reads ///////////////////////////////////////////
    private void setOwnerClock(CacheLine line, Message msg) {
        if (!DIRTY_READS)
            return;

        final long clock = msg.getMessageId();
        line.setOwnerClock(clock);

        final short owner = msg.getNode();
        final OwnerClock oc = getOwnerClock(owner);

        switch (msg.getType()) {
            case INV:
                oc.invCounter.incrementAndGet();
                break;

            case PUT:
            case PUTX:
            case MSG:
                setOwnerClockPut(oc, clock);
                break;
            default:
                break;
        }
    }

    private void setOwnerClockInv(CacheLine line, short owner) {
        final OwnerClock oc = getOwnerClock(owner);
        final long current = oc.lastPut.get();
        line.setOwnerClock(current);
    }

    private void setOwnerClockPut(Message msg) {
        final short owner = msg.getNode();
        final OwnerClock oc = getOwnerClock(owner);
        setOwnerClockPut(oc, msg.getMessageId());
    }

    private void setOwnerClockPut(OwnerClock oc, long clock) {
        for (;;) {
            final long current = oc.lastPut.get();
            if (current < 0 || clock <= current) // lastPut <=0 is a special case, set in nodeSwitched
                break;
            if (oc.lastPut.compareAndSet(current, clock)) {
                monitor.addStalePurge(oc.invCounter.get());
                oc.invCounter.set(0);
                break;
            }
        }
    }

    private void resetOwnerClock(short owner, long value) {
        OwnerClock oc = ownerClocks.get(owner);
        if (oc != null) {
            oc.lastPut.set(value);
            final int count = oc.invCounter.get();
            monitor.addStalePurge(count);
            oc.invCounter.set(0);
            LOG.debug("Resetting owner clock for {}. Purging {} lines.", owner, count);
        }
    }

    private OwnerClock getOwnerClock(short owner) {
        OwnerClock oc = ownerClocks.get(owner);
        if (oc == null) {
            oc = new OwnerClock();
            OwnerClock tmp = ownerClocks.putIfAbsent(owner, oc);
            if (tmp != null)
                oc = tmp;
        }
        return oc;
    }

    /*
     * The dirty reads mechanism enables reading invalidated data (I lines) as long as this doesn't result in inconsistent views.
     *
     * The way this is done is by keeping track of INV and PUT messages from each host. Once an invalidated line has been PUT, we
     * cannot allow any stale (I) lines from the same owner to be read (until they've all been PUT or evicted).
     */
    private boolean isPossibleInconsistencies(CacheLine line) {
        final short owner = line.getOwner();
        if (owner == -1)
            return false;
        final OwnerClock oc = ownerClocks.get(owner);
        if (oc == null)
            return false;
        long lastPut = oc.lastPut.get();
        return lastPut < 0 || line.getOwnerClock() <= lastPut; // lastPut <=0 is a special case, set in nodeSwitched
    }

    private static class OwnerClock {

        public final AtomicLong lastPut = new AtomicLong();
        public final AtomicInteger invCounter = new AtomicInteger();
    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Node Event Handling">
    /////////////////////////// Node Event Handling ///////////////////////////////////////////
    @Override
    public void nodeRemoved(final short node) {
        LOG.info("Node {} removed.", node);
        final short newOwner = hasServer ? Comm.SERVER : (short) -1;
        final NodeEvent event = new NodeEvent(node, newOwner);
        inNodeEventHandler.set(Boolean.TRUE);
        nodeEvents.add(event);
        try {
            processLines(new LinePredicate() {

                @Override
                public boolean processLine(CacheLine line) {
                    // remove pending messages from node
                    for (Iterator<LineMessage> it = getPendingMessages(line).iterator(); it.hasNext();) {
                        LineMessage message = it.next();
                        if (message.getNode() == node)
                            it.remove();
                    }
                    processLineOnNodeEvent(line, node, newOwner);
                    return true;
                }

            });
        } finally {
            nodeEvents.remove(event);
            inNodeEventHandler.remove();
        }
    }

    @Override
    public void nodeSwitched(final short node) {
        final NodeEvent event = new NodeEvent(node, node);
        inNodeEventHandler.set(Boolean.TRUE);
        nodeEvents.add(event);
        try {
            resetOwnerClock(node, -1);

            processLines(new LinePredicate() {

                @Override
                public boolean processLine(CacheLine line) {
                    // we don't inform slave of sharers, so it assumes its lines are E, therefore we must INV shared
                    // also we don't backup S lines, so we remove node from sharers
                    processLineOnNodeEvent(line, node, node);
                    return true;
                }

            });

            resetOwnerClock(node, 1); // now puts can update the clock again

        } finally {
            nodeEvents.remove(event);
            inNodeEventHandler.remove();
        }
    }

    @Override
    public void nodeAdded(short node) {
    }

    private void handleNodeEvents(CacheLine line) {
        if (inNodeEventHandler.get() == Boolean.TRUE)
            return;
        for (NodeEvent event : nodeEvents)
            processLineOnNodeEvent(line, event.node, event.newOwner);
    }

    private void processLineOnNodeEvent(CacheLine line, short node, short newOwner) {
        if (line.getState().isLessThan(State.O) && line.getOwner() == node) { // remove shared lines owned by node
            if (LOG.isDebugEnabled())
                LOG.debug("Node {} switched/removed - owned line {}. Setting to I and owner to {}", new Object[]{node, line, newOwner});

            // We must S -> I, because the dead node's slaves have E (see handleMessageBackup)
            int change = 0;
            change |= setState(line, State.I) ? LINE_STATE_CHANGED : 0;
            setNextState(line, null);
            if (node != newOwner)
                change |= setOwner(line, newOwner) ? LINE_OWNER_CHANGED : 0;
            line.setOwnerClock(0);// setOwnerClockInv(line, newOwner); - TODO ???
            handlePendingOps(line, change);
        } else if (line.getState() == State.O && line.sharers.remove(node)) {
            if (LOG.isDebugEnabled())
                LOG.debug("Node {} switched/removed - removing from sharers of line {}", node, line);
            if (line.sharers.isEmpty()) {
                setState(line, State.E);
                handlePendingOps(line, LINE_STATE_CHANGED);
            }
        }
    }

    private static final class NodeEvent {

        public final short node;
        public final short newOwner;

        public NodeEvent(short node, short newOwner) {
            this.node = node;
            this.newOwner = newOwner;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (obj == this)
                return true;
            if (!(obj instanceof NodeEvent))
                return false;
            return this.node == ((NodeEvent) obj).node;
        }

        @Override
        public int hashCode() {
            return node;
        }

    }
    //</editor-fold>

    //<editor-fold defaultstate="collapsed" desc="Implementation details">
    /////////////////////////// Implementation details ///////////////////////////////////////////
    private boolean setNextState(CacheLine line, State nextState) {
        if (line.nextState == nextState)
            return false;
        if (line.nextState == null || nextState == null || line.nextState.isLessThan(nextState)) {
            line.nextState = nextState;
            if (nextState == State.S | nextState == State.O)
                monitor.addMiss();
            if (nextState == State.E)
                monitor.addInvalidate(line.sharers.size());
            return true;
        } else
            return false;
    }

    private boolean setState(CacheLine line, State state) {
        if (line.nextState != null && (line.nextState == state || line.nextState.isLessThan(state)))
            line.nextState = null;
        if (line.state != state) {
            if (LOG.isDebugEnabled())
                LOG.debug("Set state {} {} -> {}", new Object[]{hex(line.getId()), line.state, state});

            if (!state.isLessThan(State.O) && line.getState().isLessThan(State.O)) {
                owned.put(line.getId(), line);
                shared.remove(line.getId());
            } else if (state.isLessThan(State.O) && !line.getState().isLessThan(State.O)) {
                shared.put(line.getId(), line);
                owned.remove(line.getId());
            }

//            if (state.isLessThan(State.O) && !line.getState().isLessThan(State.O))
//                line.timeAccessed = System.currentTimeMillis();

            line.state = state;
            if (line.sharers == null || !state.isLessThan(State.O))
                line.sharers = allocateSharerSet(SHARER_SET_DEFAULT_SIZE);
            else if (line.sharers != null || state.isLessThan(State.O)) {
                deallocateSharerSet(line.id, line.sharers);
                line.sharers = null;
            }
            if (state == State.I && !line.is(CacheLine.DELETED))
                fireLineInvalidated(line);
            return true;
        } else
            return false;
    }

    // should be called AFTER setState
    private boolean setOwner(CacheLine line, short owner) {
        short oldOwner = line.owner;
        if (owner != oldOwner) {
            if (LOG.isDebugEnabled())
                LOG.debug("Set owner {} {} -> {}", new Object[]{hex(line.getId()), line.owner, owner});
            line.owner = owner;
            return true;
        } else
            return false;
    }

    private void accessLine(CacheLine line) {
//        if (line != null) {
//            if (line.getState().isLessThan(State.O))
//                line.timeAccessed = System.currentTimeMillis();
//        }
    }

    private boolean writeData(CacheLine line, Object data) {
        if (data == null)
            return writeNull(line);
        else if (data instanceof Persistable)
            return writeData(line, (Persistable) data);
        else if (data instanceof ByteBuffer)
            return writeData(line, (ByteBuffer) data);
        else
            return writeData(line, (byte[]) data);
    }

    private boolean writeData(CacheLine line, byte[] data) {
        if (data.length > maxItemSize)
            throw new IllegalArgumentException("Data size is " + data.length + " bytes and exceeds the limit of " + maxItemSize + " bytes.");

        if (compareBeforeWrite) {
            if (line.data != null && data.length == line.data.remaining()) {
                final int p = line.data.position();
                boolean modified = false;
                for (int i = 0; i < data.length; i++) {
                    if (line.data.get(p + i) != data[i]) {
                        modified = true;
                        break;
                    }
                }
                if (!modified)
                    return false;
            }
        }

        allocateLineData(line, data.length);
        line.data.put(data);
        line.data.flip();
        return true;
    }

    private boolean writeData(CacheLine line, ByteBuffer data) {
        if (data.remaining() > maxItemSize)
            throw new IllegalArgumentException("Data size is " + data.remaining() + " bytes and exceeds the limit of " + maxItemSize + " bytes.");

        if (compareBeforeWrite) {
            if (line.data != null && data.remaining() == line.data.remaining()) {
                final int p1 = line.data.position();
                final int p2 = data.position();
                boolean modified = false;
                for (int i = 0; i < data.remaining(); i++) {
                    if (line.data.get(p1 + i) != data.get(p2 + i)) {
                        modified = true;
                        break;
                    }
                }
                if (!modified)
                    return false;
            }
        }

        allocateLineData(line, data.remaining());
        line.data.put(data);
        line.data.flip();
        return true;
    }

    private boolean writeData(CacheLine line, Persistable object) {
        if (object.size() > maxItemSize)
            throw new IllegalArgumentException("Object size is " + object.size() + " bytes and exceeds the limit of " + maxItemSize + " bytes.");

        if (compareBeforeWrite) {
            final Checksum chksm = getChecksum();
            if (line.data != null && object.size() == line.data.remaining()) {
                final int n = line.data.remaining();

                chksm.update(line.data);
                line.data.rewind();
                final byte[] hash = chksm.getChecksum();

                object.write(line.data);
                line.data.flip();

                chksm.reset();
                chksm.update(line.data);
                line.data.rewind();

                if (!Arrays.equals(hash, chksm.getChecksum()))
                    return true;
                return false;
            }
        }

        allocateLineData(line, object.size());
        object.write(line.data);
        line.data.flip();
        return true;
    }

    private boolean writeNull(CacheLine line) {
        if (line.data == null)
            return false;
        final int oldSize = line.size();
        deallocateStorage(line.id, line.data);
        line.data = null;
        if (line.getState().isLessThan(State.O)) // => state must be set before this is called
            putLine(line.id, line, oldSize, 0); // size changed
        return true;
    }

    private void allocateLineData(CacheLine line, int size) {
        final int oldSize = line.size();
        if (line.data != null) {
            if (line.data.capacity() >= size && line.data.capacity() < size * 4) {
                if (LOG.isDebugEnabled())
                    LOG.debug("Reusing (clearing) storage for line {}. Storage: {} bytes. Data: {} bytes", new Object[]{hex(line.getId()), line.data.capacity(), size});
                line.data.clear();
            } else {
                deallocateStorage(line.id, line.data);
                line.data = null;
            }
        }
        boolean resized = false;
        if (line.data == null) {
            if (LOG.isDebugEnabled())
                LOG.debug("Allocating storage ({} bytes) for line {}", size, hex(line.getId()));
            line.data = allocateStorage(size);
            resized = true;
        }
        line.data.limit(size);
        if (resized && line.getState().isLessThan(State.O)) // => state must be set before this is called
            putLine(line.id, line, oldSize, line.size()); // size changed
    }

    /**
     * DO NOT modify returned array.
     */
    private byte[] readData(CacheLine line) {
        accessLine(line);

        if (line.data == null)
            return null;
        byte[] data = new byte[line.data.remaining()];
        line.data.get(data);
        line.data.rewind();
        return data;
    }

    private void readData(CacheLine line, Persistable object) {
        accessLine(line);

        final ByteBuffer buffer = line.data != null ? line.data : EMPTY_BUFFER;
        if (object instanceof VersionedPersistable)
            ((VersionedPersistable) object).read(line.getVersion(), buffer);
        else
            object.read(buffer);
        line.rewind();
    }

    private CacheLine createNewCacheLine(Op op) {
        CacheLine line = allocateCacheLine();
        line.id = op.line;
        return putLine(op.line, line, 0, 0);
    }

    private CacheLine createNewCacheLine(Message message) {
        final long id = ((LineMessage) message).getLine();
        CacheLine line = allocateCacheLine();
        line.id = id;
        return putLine(id, line, 0, 0);
    }

    // visible for testing
    void evictLine(CacheLine line, boolean invack) {
        final long id = line.getId();
        final int oldSize = line.size();
        discardLine(line, invack);
        removeLine(id, line, oldSize);
    }

    private void discardLine(CacheLine line, boolean invack) {
        LOG.debug("Evicted {}", line);
        fireLineEvicted(line);
        final long id = line.getId();
        deallocateStorage(id, line.data);
        if (invack && line.getState() == State.S)
            send(Message.INVACK(line.getOwner(), line.getId()));
        clearLine(line);
        deallocateCacheLine(id, line);
    }

    private void clearLine(CacheLine line) {
        if (line.sharers != null)
            deallocateSharerSet(line.id, line.sharers);
        line.id = 0;
        line.clearFlags();
        //line.timeAccessed = 0;
        line.state = State.I;
        line.nextState = null;
        line.owner = -1;
        line.sharers = null;
        line.version = 0;
        line.data = null;
    }

    void lockLine(CacheLine line, Transaction txn) {
        LOG.debug("Locking line {}", line);
        LOG.trace("Locked:", new Throwable());
        line.lock();
        if (txn != null)
            txn.add(line.getId());
    }

    boolean unlockLine(CacheLine line, Transaction txn) {
        LOG.debug("Unlocking line {}", line);
        LOG.trace("Unlocked:", new Throwable());
        assert txn == null || txn.contains(line.getId());
        return line.unlock();
    }

    void send(Message message) {
        LOG.debug("Sending: {}", message);
        try {
            comm.send(message);
        } catch (NodeNotFoundException e) {
            final Message response = genResponse(message);
            LOG.debug("Auto response: {} (to: {})", response, message);
            if (response != null)
                receive(shortCircuitMessage(message.getNode(), response));
        }
        monitor.addMessageSent(message.getType());
    }

    private void receiveShortCircuit() {
        Queue<Message> ms = this.shortCircuitMessage.get();
        if (ms != null) {
            while (!ms.isEmpty()) {
                Message m = ms.remove();
                receive1(m);
            }
        }
        this.shortCircuitMessage.remove();
    }

    private Message genResponse(Message message) {
        switch (message.getType()) {
            case INV:
                return Message.INVACK((Message.INV) message);
            case GET:
            case GETX:
                return Message.CHNGD_OWNR(((LineMessage) message), ((LineMessage) message).getLine(), (short) -1, false);
            default:
                return null;
            // don't send message
            }
    }

    private Message shortCircuitMessage(short node, Message message) {
        message.setIncoming();
        message.setNode(node);
        return message;
    }

    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    // visible for testing
    CacheLine getLine(long id) {
        CacheLine line = owned.get(id);
        if (line == null)
            line = shared.get(id);
        return line;
    }

    private CacheLine putLine(long id, Cache.CacheLine line, int oldSize, int newSize) {
        final CacheLine old;
        if (line.getState().isLessThan(State.O)) {
            old = shared.put(id, line); // to make sure eviction data is updated we must put rather than putIfAbsent
            if (old != null && old != line)
                evictLine(old, false);
            return line;
        } else {
            old = owned.putIfAbsent(id, (CacheLine) line);
            if (old != null && old != line) {
                evictLine(line, false);
                return old;
            } else
                return line;
        }
    }

    // visible for testing
    void removeLine(long id, CacheLine line, int oldSize) {
        if (owned.remove(id) == null)
            shared.remove(id);
    }

    private void addPendingOp(CacheLine line, Op op) {
        if (op.hasFuture())
            return;
        op.createFuture();

        ArrayList<Op> ops = pendingOps.get(op.line);
        if (ops == null) {
            ops = new ArrayList<Op>();
            pendingOps.put(op.line, ops);
        }
        ops.add(op);
    }

    /**
     * Returns, but DOES NOT CLEAR the pending ops queue.
     */
    private Collection<Op> getPendingOps(CacheLine line) {
        ArrayList<Op> ops = pendingOps.get(line.getId());
        return (ops != null ? ops : Collections.EMPTY_LIST);
    }

    private void removePendingOp(CacheLine line, Op op) {
        ArrayList<Op> ops = pendingOps.get(op.line);
        if (ops == null)
            return;
        ops.remove(op);
        if (ops.isEmpty())
            pendingOps.remove(op.line);
    }

    private void addPendingMessage(CacheLine line, LineMessage message) {
        HashSet<LineMessage> msgs = pendingMessages.get(line.getId());
        if (msgs == null) {
            msgs = new LinkedHashSet<LineMessage>();
            pendingMessages.put(line.getId(), msgs);
        }
        msgs.add(message);
    }

    private boolean hasPendingMessages(CacheLine line) {
        final Collection<LineMessage> msgs = pendingMessages.get(line.getId());
        return msgs != null && !msgs.isEmpty();
    }

    /**
     * Returns and CLEARS the pending messages queue.
     */
    private Set<LineMessage> getAndClearPendingMessages(CacheLine line) {
        Set<LineMessage> msgs = pendingMessages.remove(line.getId());
        if (msgs == null)
            msgs = Collections.emptySet();
        return msgs;
    }

    private Set<LineMessage> getPendingMessages(CacheLine line) {
        Set<LineMessage> msgs = pendingMessages.get(line.getId());
        if (msgs == null)
            msgs = Collections.emptySet();
        return msgs;
    }

    interface LinePredicate {

        boolean processLine(CacheLine line);

    }

    private void processLines(LinePredicate lp) {
        for (ConcurrentMap<Long, CacheLine> map : new ConcurrentMap[]{owned, shared}) {
            for (Iterator<CacheLine> it = map.values().iterator(); it.hasNext();) {
                CacheLine line = it.next();
                final boolean retain;
                synchronized (line) {
                    retain = lp.processLine(line);
                    if (!retain)
                        discardLine(line, false);
                }
                if (!retain)
                    it.remove();
            }
        }
    }

    private short myNodeId() {
        return getCluster().getMyNodeId();
    }

    private CacheLine allocateCacheLine() {
        CacheLine line;
        if (freeLineList == null)
            line = new CacheLine();
        else {
            line = freeLineList.pollFirst();
            if (line == null)
                line = new CacheLine();
        }
        clearLine(line);
        return line;
    }

    private void deallocateCacheLine(long id, Cache.CacheLine line) {
        if (freeLineList == null)
            return;

        freeLineList.addFirst(line);
    }

    private TShortHashSet allocateSharerSet(int size) {
        if (freeSharerSetList == null)
            return new TShortHashSet(size);

        TShortHashSet sharers = freeSharerSetList.pollFirst();
        if (sharers != null)
            return sharers;
        return new TShortHashSet(size);
    }

    private void deallocateSharerSet(long id, TShortHashSet sharers) {
        if (freeSharerSetList == null)
            return;

        freeSharerSetList.addFirst(sharers);
    }

    ByteBuffer allocateStorage(int length) {
        return storage.allocateStorage(length);
    }

    void deallocateStorage(long id, ByteBuffer buffer) {
        if (buffer != null) {
            if (LOG.isDebugEnabled())
                LOG.debug("Deallocating storage for line {}", hex(id));
            storage.deallocateStorage(id, buffer);
        }
    }

    private void fireLineInvalidated(CacheLine line) {
        if (line.getListener() != null) {
            try {
                line.getListener().invalidated(line.getId());
            } catch (Exception e) {
                LOG.error("Listener threw an exception.", e);
            }
        }
        for (CacheListener listener : listeners) {
            try {
                listener.invalidated(line.getId());
            } catch (Exception e) {
                LOG.error("Listener threw an exception.", e);
            }
        }
    }

    private void fireLineReceived(CacheLine line) {
        final long id = line.getId();
        final long version = line.getVersion();
        final ByteBuffer data = line.data;

        if (line.getListener() != null) {
            try {
                line.getListener().received(id, version, data);
            } catch (Exception e) {
                LOG.error("Listener threw an exception.", e);
            }
            line.rewind();
        }
        for (CacheListener listener : listeners) {
            try {
                listener.received(id, version, data);
            } catch (Exception e) {
                LOG.error("Listener threw an exception.", e);
            }
            line.rewind();
        }
    }

    private void fireLineEvicted(CacheLine line) {
        if (line.getListener() != null) {
            try {
                line.getListener().evicted(line.getId());
            } catch (Exception e) {
                LOG.error("Listener threw an exception.", e);
            }
        }
        for (CacheListener listener : listeners) {
            try {
                listener.evicted(line.getId());
            } catch (Exception e) {
                LOG.error("Listener threw an exception.", e);
            }
        }
    }

    private static ByteBuffer readOnly(ByteBuffer buffer) {
        return buffer != null ? buffer.asReadOnlyBuffer() : null;
    }

    public static boolean isReserved(long id) {
        return id <= MAX_RESERVED_REF_ID;
    }

    private static class IrrelevantStateException extends Exception {
    }

    private static final IrrelevantStateException IRRELEVANT_STATE = new IrrelevantStateException();

    private void relevantStates(CacheLine line, State... states) throws IrrelevantStateException {
        final State state = line.state;
        for (State s : states) {
            if (state == s)
                return;
        }
        throw IRRELEVANT_STATE;
    }
    //</editor-fold>
}
