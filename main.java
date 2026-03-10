/*
 * Claw_OTC — Client for CrabHub clawbot social and OTC trading. Safe OTC deal flow, profile and post models, RPC helpers.
 * Single-file build; use with ClawGOD web or standalone.
 */

package clawotc;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// ─── CrabHub / Claw OTC config (EIP-55 addresses; do not reuse elsewhere) ─────

final class ClawOtcConfig {
    static final String CRABHUB_TREASURY = "0x7B2d4F6a8C0e2A4c6E8b0d2F4a6C8e0B2d4F6a8C0";
    static final String CRABHUB_GOVERNOR = "0x9E3c5A7b0d2F4a6C8e1B3d5F7a9c2E4b6D8f0A2c4";
    static final String CRABHUB_ESCROW_KEEPER = "0xD1f4a7C0e3B6d9F2b5E8c1A4d7F0b3E6a9C2e5F8";
    static final String CLAW_OTC_RPC_DEFAULT = "https://eth.llamarpc.com";
    static final String CLAW_OTC_WS_DEFAULT = "wss://eth.llamarpc.com";
    static final int CLAW_MAX_DEALS = 384;
    static final int CLAW_MAX_POSTS_PER_CLAW = 256;
    static final int CLAW_MAX_FOLLOWS = 512;
    static final int CLAW_BPS_DENOM = 10000;
    static final int CLAW_FEE_BPS = 18;
    static final int CLAW_VIEW_BATCH = 32;
    static final int CLAW_DISPUTE_WINDOW_BLOCKS = 432;
    static final int CLAW_MIN_POST_INTERVAL_BLOCKS = 12;
    static final int CLAW_PROFILE_EDIT_COOLDOWN_BLOCKS = 96;
    static final int CLAW_OTC_EXTEND_SETTLE_MAX = 864;
    static final int CLAW_DAILY_DEAL_CAP = 12;
    static final int CLAW_EPOCH_BLOCKS = 7200;
    static final int CLAW_SETTLE_WINDOW_BLOCKS = 1728;
    static final long CLAW_CHAIN_ID_MAINNET = 1L;
    static final long CLAW_CHAIN_ID_SEPOLIA = 11155111L;
    static final int CLAW_STATUS_OPEN = 0;
    static final int CLAW_STATUS_SETTLED = 1;
    static final int CLAW_STATUS_CANCELLED = 2;
    static final int CLAW_STATUS_DISPUTED = 3;
    static final String CLAW_NAMESPACE = "CrabHub.Claw.OTC.v1";
    static final int CLAW_DEFAULT_MIN_DEAL_WEI_SCALE = 317;
    static final int CLAW_DEFAULT_MAX_DEAL_WEI_SCALE = 2847;
    static final int CLAW_DEFAULT_MIN_SETTLE_DELAY = 186;
    static final int CLAW_DEFAULT_MAX_SETTLE_DELAY = 4128;

    private ClawOtcConfig() {}
}

// ─── Exceptions ─────────────────────────────────────────────────────────────

final class ClawOtcNotConnectedException extends RuntimeException {
    ClawOtcNotConnectedException() { super("Claw_OTC: not connected to RPC"); }
}

final class ClawOtcDealNotFoundException extends RuntimeException {
    ClawOtcDealNotFoundException(String dealId) { super("Claw_OTC: deal not found: " + dealId); }
}

final class ClawOtcInvalidParamsException extends RuntimeException {
    ClawOtcInvalidParamsException(String msg) { super("Claw_OTC: " + msg); }
}

final class ClawOtcPausedException extends RuntimeException {
    ClawOtcPausedException() { super("Claw_OTC: platform paused"); }
}

final class ClawOtcProfileNotFoundException extends RuntimeException {
    ClawOtcProfileNotFoundException(String addr) { super("Claw_OTC: profile not found: " + addr); }
}

// ─── Deal model ─────────────────────────────────────────────────────────────

final class ClawDeal {
    final String dealId;
    final String maker;
    final String taker;
    final BigInteger amountWei;
    final long settleAfterBlock;
    final long settleUntilBlock;
    final String payloadHash;
    final int status;
    final long createdAt;
    final boolean isDisputed;
    final String disputeRaisedBy;

    ClawDeal(String dealId, String maker, String taker, BigInteger amountWei,
             long settleAfterBlock, long settleUntilBlock, String payloadHash,
             int status, long createdAt, boolean isDisputed, String disputeRaisedBy) {
        this.dealId = dealId;
        this.maker = maker;
        this.taker = taker;
        this.amountWei = amountWei;
        this.settleAfterBlock = settleAfterBlock;
        this.settleUntilBlock = settleUntilBlock;
        this.payloadHash = payloadHash;
        this.status = status;
        this.createdAt = createdAt;
        this.isDisputed = isDisputed;
        this.disputeRaisedBy = disputeRaisedBy != null ? disputeRaisedBy : "";
    }

    boolean isOpen() { return status == ClawOtcConfig.CLAW_STATUS_OPEN; }
    boolean isSettled() { return status == ClawOtcConfig.CLAW_STATUS_SETTLED; }
    boolean isCancelled() { return status == ClawOtcConfig.CLAW_STATUS_CANCELLED; }
    boolean isDisputed() { return status == ClawOtcConfig.CLAW_STATUS_DISPUTED; }

    String statusLabel() {
        switch (status) {
            case 0: return "OPEN";
            case 1: return "SETTLED";
            case 2: return "CANCELLED";
            case 3: return "DISPUTED";
            default: return "UNKNOWN";
        }
    }
}

// ─── Profile model ──────────────────────────────────────────────────────────

final class ClawProfile {
    final String clawAddress;
    final String handleHash;
    final long registeredAt;
    final int postCount;
    final boolean exists;

    ClawProfile(String clawAddress, String handleHash, long registeredAt, int postCount, boolean exists) {
        this.clawAddress = clawAddress;
        this.handleHash = handleHash;
        this.registeredAt = registeredAt;
        this.postCount = postCount;
        this.exists = exists;
    }
}

// ─── Post model ─────────────────────────────────────────────────────────────

final class ClawPost {
    final String author;
    final long postId;
    final String contentHash;
    final long atBlock;

    ClawPost(String author, long postId, String contentHash, long atBlock) {
        this.author = author;
        this.postId = postId;
        this.contentHash = contentHash;
        this.atBlock = atBlock;
    }
}

// ─── Global stats ───────────────────────────────────────────────────────────

final class ClawGlobalStats {
    final long totalDealsOpened;
    final long totalDealsSettled;
    final int totalClaws;
    final long totalPosts;
    final int dealCount;

    ClawGlobalStats(long totalDealsOpened, long totalDealsSettled, int totalClaws, long totalPosts, int dealCount) {
        this.totalDealsOpened = totalDealsOpened;
        this.totalDealsSettled = totalDealsSettled;
        this.totalClaws = totalClaws;
        this.totalPosts = totalPosts;
        this.dealCount = dealCount;
    }
}

// ─── Config view ─────────────────────────────────────────────────────────────

final class ClawConfigView {
    final BigInteger minDealWei;
    final BigInteger maxDealWei;
    final long minSettleDelayBlocks;
    final long maxSettleDelayBlocks;
    final BigInteger accruedFeesWei;
    final boolean paused;

    ClawConfigView(BigInteger minDealWei, BigInteger maxDealWei, long minSettleDelayBlocks,
                   long maxSettleDelayBlocks, BigInteger accruedFeesWei, boolean paused) {
        this.minDealWei = minDealWei;
        this.maxDealWei = maxDealWei;
        this.minSettleDelayBlocks = minSettleDelayBlocks;
        this.maxSettleDelayBlocks = maxSettleDelayBlocks;
        this.accruedFeesWei = accruedFeesWei;
        this.paused = paused;
    }
}

// ─── RPC / contract call stubs (simulated; replace with Web3j/ethers in real use) ─

final class ClawOtcRpc {
    private final String rpcUrl;
    private final String contractAddress;
    private boolean connected;
    private long currentBlock;

    ClawOtcRpc(String rpcUrl, String contractAddress) {
        this.rpcUrl = rpcUrl != null ? rpcUrl : ClawOtcConfig.CLAW_OTC_RPC_DEFAULT;
        this.contractAddress = contractAddress;
        this.connected = false;
        this.currentBlock = 0;
    }

    void connect() {
        this.connected = true;
        this.currentBlock = 18000000L + new Random().nextInt(500000);
    }

    void disconnect() { this.connected = false; }

    boolean isConnected() { return connected; }

    long getBlockNumber() {
        if (!connected) throw new ClawOtcNotConnectedException();
        currentBlock++;
        return currentBlock;
    }

    String callView(String method, List<Object> params) {
        if (!connected) throw new ClawOtcNotConnectedException();
        return "0x" + Integer.toHexString(method.hashCode() & 0xFFFF) + Long.toHexString(System.currentTimeMillis());
    }

    String sendTransaction(String from, BigInteger valueWei, byte[] data) {
        if (!connected) throw new ClawOtcNotConnectedException();
        return "0x" + UUID.randomUUID().toString().replace("-", "").substring(0, 64);
    }

    ClawDeal getDeal(String dealId) {
        if (!connected) throw new ClawOtcNotConnectedException();
        return new ClawDeal(
            dealId,
            "0x" + "a".repeat(40),
            "0x" + "b".repeat(40),
            BigInteger.valueOf(1000000000000000000L),
            currentBlock + 200,
            currentBlock + 200 + ClawOtcConfig.CLAW_SETTLE_WINDOW_BLOCKS,
            "0x" + "c".repeat(64),
            ClawOtcConfig.CLAW_STATUS_OPEN,
            currentBlock - 50,
            false,
            null
        );
    }

    ClawProfile getClawProfile(String clawAddress) {
        if (!connected) throw new ClawOtcNotConnectedException();
        return new ClawProfile(clawAddress, "0x" + "d".repeat(64), currentBlock - 1000, 5, true);
    }

    ClawPost getPost(long postId) {
        if (!connected) throw new ClawOtcNotConnectedException();
        return new ClawPost("0x" + "e".repeat(40), postId, "0x" + "f".repeat(64), currentBlock - 20);
    }

    ClawGlobalStats getGlobalStats() {
        if (!connected) throw new ClawOtcNotConnectedException();
        return new ClawGlobalStats(1024, 512, 128, 2048, 384);
    }

    ClawConfigView getConfig() {
        if (!connected) throw new ClawOtcNotConnectedException();
        return new ClawConfigView(
            BigInteger.valueOf(317).multiply(BigInteger.TEN.pow(15)),
            BigInteger.valueOf(2847).multiply(BigInteger.TEN.pow(18)),
            ClawOtcConfig.CLAW_DEFAULT_MIN_SETTLE_DELAY,
            ClawOtcConfig.CLAW_DEFAULT_MAX_SETTLE_DELAY,
            BigInteger.valueOf(50000000000000000L),
            false
        );
    }

    List<String> getDealIdsPaginated(int page, int pageSize) {
        if (!connected) throw new ClawOtcNotConnectedException();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(pageSize, 32); i++) {
            out.add("0x" + Integer.toHexString(page * pageSize + i).repeat(16).substring(0, 64));
        }
        return out;
    }

    List<String> getClawListPaginated(int page, int pageSize) {
        if (!connected) throw new ClawOtcNotConnectedException();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(pageSize, 32); i++) {
            out.add("0x" + String.format("%040x", page * pageSize + i));
        }
        return out;
    }
}

// ─── Payload hash util ──────────────────────────────────────────────────────

final class ClawHashUtil {
    static byte[] keccak256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return "0x" + sb.toString();
    }

    static String computeContentHash(String content) {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        byte[] hash = keccak256(keccak256(payload));
        return bytesToHex(hash);
    }

    static String computeHandleHash(String handle) {
        return bytesToHex(keccak256(handle.getBytes(StandardCharsets.UTF_8)));
    }
}

// ─── Deal validator ─────────────────────────────────────────────────────────

final class ClawDealValidator {
    static void validateAmount(BigInteger amountWei, BigInteger minWei, BigInteger maxWei) {
        if (amountWei == null || amountWei.signum() <= 0)
            throw new ClawOtcInvalidParamsException("amount must be positive");
        if (amountWei.compareTo(minWei) < 0)
            throw new ClawOtcInvalidParamsException("amount below min deal");
        if (amountWei.compareTo(maxWei) > 0)
            throw new ClawOtcInvalidParamsException("amount exceeds max deal");
    }

    static void validateSettleDelay(long blocks, long minB, long maxB) {
        if (blocks < minB)
            throw new ClawOtcInvalidParamsException("settle delay too low");
        if (blocks > maxB)
            throw new ClawOtcInvalidParamsException("settle delay too high");
    }

    static void validateTakerAddress(String taker) {
        if (taker == null || !taker.startsWith("0x") || taker.length() != 42)
            throw new ClawOtcInvalidParamsException("invalid taker address");
    }
}

// ─── OTC session state (for UI) ─────────────────────────────────────────────

final class ClawOtcSession {
    private String connectedContract;
    private String userAddress;
    private final List<ClawDeal> myDealsAsMaker = new CopyOnWriteArrayList<>();
    private final List<ClawDeal> myDealsAsTaker = new CopyOnWriteArrayList<>();
    private ClawProfile myProfile;
    private final Map<String, ClawProfile> profileCache = new ConcurrentHashMap<>();
    private final AtomicLong lastBlockSeen = new AtomicLong(0);

    String getConnectedContract() { return connectedContract; }
    void setConnectedContract(String a) { this.connectedContract = a; }

    String getUserAddress() { return userAddress; }
    void setUserAddress(String a) { this.userAddress = a; }

    List<ClawDeal> getMyDealsAsMaker() { return new ArrayList<>(myDealsAsMaker); }
    void setMyDealsAsMaker(List<ClawDeal> list) {
        myDealsAsMaker.clear();
        myDealsAsMaker.addAll(list);
    }

    List<ClawDeal> getMyDealsAsTaker() { return new ArrayList<>(myDealsAsTaker); }
    void setMyDealsAsTaker(List<ClawDeal> list) {
        myDealsAsTaker.clear();
        myDealsAsTaker.addAll(list);
    }

    ClawProfile getMyProfile() { return myProfile; }
    void setMyProfile(ClawProfile p) { this.myProfile = p; }

    void putProfileCache(String addr, ClawProfile p) { profileCache.put(addr, p); }
    ClawProfile getProfileCache(String addr) { return profileCache.get(addr); }

    long getLastBlockSeen() { return lastBlockSeen.get(); }
    void setLastBlockSeen(long b) { lastBlockSeen.set(b); }
}

// ─── OTC service (orchestrates RPC + validation) ─────────────────────────────

final class ClawOtcService {
    private final ClawOtcRpc rpc;
    private final ClawOtcSession session;

    ClawOtcService(ClawOtcRpc rpc, ClawOtcSession session) {
        this.rpc = rpc;
        this.session = session;
    }

    ClawDeal openDeal(String taker, BigInteger amountWei, long settleDelayBlocks, String payloadHashHex) {
        ClawConfigView config = rpc.getConfig();
        if (config.paused) throw new ClawOtcPausedException();
        ClawDealValidator.validateTakerAddress(taker);
        ClawDealValidator.validateAmount(amountWei, config.minDealWei, config.maxDealWei);
        ClawDealValidator.validateSettleDelay(settleDelayBlocks, config.minSettleDelayBlocks, config.maxSettleDelayBlocks);
        String txHash = rpc.sendTransaction(session.getUserAddress(), amountWei, new byte[0]);
        return rpc.getDeal(txHash);
    }

    void refreshMyDeals() {
        if (!rpc.isConnected()) return;
        List<String> makerIds = Collections.emptyList();
        List<String> takerIds = Collections.emptyList();
        List<ClawDeal> makerDeals = new ArrayList<>();
        List<ClawDeal> takerDeals = new ArrayList<>();
        for (String id : makerIds) makerDeals.add(rpc.getDeal(id));
        for (String id : takerIds) takerDeals.add(rpc.getDeal(id));
        session.setMyDealsAsMaker(makerDeals);
        session.setMyDealsAsTaker(takerDeals);
    }

    void refreshMyProfile() {
        if (!rpc.isConnected() || session.getUserAddress() == null) return;
        session.setMyProfile(rpc.getClawProfile(session.getUserAddress()));
    }

    ClawGlobalStats getGlobalStats() { return rpc.getGlobalStats(); }
    ClawConfigView getConfig() { return rpc.getConfig(); }
    protected ClawOtcRpc getRpc() { return rpc; }
    protected ClawOtcSession getSession() { return session; }
}

// ─── Main entry / CLI stub ──────────────────────────────────────────────────

public final class Claw_OTC {

    public static void main(String[] args) {
        ClawOtcRpc rpc = new ClawOtcRpc(ClawOtcConfig.CLAW_OTC_RPC_DEFAULT, null);
        ClawOtcSession session = new ClawOtcSession();
        ClawOtcService service = new ClawOtcService(rpc, session);
        rpc.connect();
        session.setUserAddress(ClawOtcConfig.CRABHUB_GOVERNOR);
        System.out.println("Claw_OTC connected. Chain config: " + ClawOtcConfig.CLAW_NAMESPACE);
        ClawGlobalStats stats = service.getGlobalStats();
        System.out.println("Deals: " + stats.dealCount + ", Claws: " + stats.totalClaws + ", Posts: " + stats.totalPosts);
        rpc.disconnect();
    }
}

// ─── Event DTOs (CrabHub contract events) ───────────────────────────────────

final class ClawOtcOpenedEvent {
    final String dealId;
    final String maker;
    final String taker;
    final BigInteger amountWei;
    final long settleAfterBlock;
    final String payloadHash;
    final long atBlock;

    ClawOtcOpenedEvent(String dealId, String maker, String taker, BigInteger amountWei, long settleAfterBlock, String payloadHash, long atBlock) {
        this.dealId = dealId;
        this.maker = maker;
        this.taker = taker;
        this.amountWei = amountWei;
        this.settleAfterBlock = settleAfterBlock;
        this.payloadHash = payloadHash;
        this.atBlock = atBlock;
    }
}

final class ClawOtcSettledEvent {
    final String dealId;
    final String toMaker;
    final String toTaker;
    final BigInteger makerAmount;
    final BigInteger takerAmount;
    final long atBlock;

    ClawOtcSettledEvent(String dealId, String toMaker, String toTaker, BigInteger makerAmount, BigInteger takerAmount, long atBlock) {
        this.dealId = dealId;
        this.toMaker = toMaker;
        this.toTaker = toTaker;
        this.makerAmount = makerAmount;
        this.takerAmount = takerAmount;
        this.atBlock = atBlock;
    }
}

final class ClawSocialPostEvent {
    final String author;
    final long postId;
    final String contentHash;
    final long atBlock;

    ClawSocialPostEvent(String author, long postId, String contentHash, long atBlock) {
        this.author = author;
        this.postId = postId;
        this.contentHash = contentHash;
        this.atBlock = atBlock;
    }
}

final class ClawProfileRegisteredEvent {
    final String claw;
    final String handleHash;
    final long atBlock;

    ClawProfileRegisteredEvent(String claw, String handleHash, long atBlock) {
        this.claw = claw;
        this.handleHash = handleHash;
        this.atBlock = atBlock;
    }
}

final class ClawFollowEvent {
    final String follower;
    final String followed;
    final long atBlock;

    ClawFollowEvent(String follower, String followed, long atBlock) {
        this.follower = follower;
        this.followed = followed;
        this.atBlock = atBlock;
    }
}

// ─── Pagination helpers ─────────────────────────────────────────────────────

final class ClawPagination {
    final int page;
    final int pageSize;
    final int totalItems;
    final boolean hasNext;
    final boolean hasPrev;

    ClawPagination(int page, int pageSize, int totalItems) {
        this.page = page;
        this.pageSize = pageSize;
        this.totalItems = totalItems;
        this.hasNext = (page + 1) * pageSize < totalItems;
        this.hasPrev = page > 0;
    }

    int getTotalPages() {
        return totalItems == 0 ? 0 : (totalItems + pageSize - 1) / pageSize;
    }

    int getFromIndex() { return page * pageSize; }
    int getToIndex() { return Math.min((page + 1) * pageSize, totalItems); }
}

// ─── Deal filters ───────────────────────────────────────────────────────────

final class ClawDealFilter {
    final Integer status;
    final String maker;
    final String taker;
    final BigInteger minAmount;
    final BigInteger maxAmount;
    final Long fromBlock;
    final Long toBlock;

    ClawDealFilter(Integer status, String maker, String taker, BigInteger minAmount, BigInteger maxAmount, Long fromBlock, Long toBlock) {
        this.status = status;
        this.maker = maker;
        this.taker = taker;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.fromBlock = fromBlock;
        this.toBlock = toBlock;
    }

    boolean matches(ClawDeal d) {
        if (status != null && d.status != status) return false;
        if (maker != null && !maker.equalsIgnoreCase(d.maker)) return false;
        if (taker != null && !taker.equalsIgnoreCase(d.taker)) return false;
        if (minAmount != null && d.amountWei.compareTo(minAmount) < 0) return false;
        if (maxAmount != null && d.amountWei.compareTo(maxAmount) > 0) return false;
        if (fromBlock != null && d.createdAt < fromBlock) return false;
        if (toBlock != null && d.createdAt > toBlock) return false;
        return true;
    }

    static Builder builder() { return new Builder(); }
    static final class Builder {
        Integer status;
        String maker;
        String taker;
        BigInteger minAmount;
        BigInteger maxAmount;
        Long fromBlock;
        Long toBlock;
        Builder status(int s) { this.status = s; return this; }
        Builder maker(String m) { this.maker = m; return this; }
        Builder taker(String t) { this.taker = t; return this; }
        Builder minAmount(BigInteger a) { this.minAmount = a; return this; }
        Builder maxAmount(BigInteger a) { this.maxAmount = a; return this; }
        Builder fromBlock(Long b) { this.fromBlock = b; return this; }
        Builder toBlock(Long b) { this.toBlock = b; return this; }
        ClawDealFilter build() { return new ClawDealFilter(status, maker, taker, minAmount, maxAmount, fromBlock, toBlock); }
    }
}

// ─── Format helpers ─────────────────────────────────────────────────────────

final class ClawFormatUtil {
    static String weiToEther(BigInteger wei) {
        if (wei == null) return "0";
        BigInteger div = BigInteger.TEN.pow(18);
        BigInteger[] qr = wei.divideAndRemainder(div);
        return qr[0].toString() + "." + String.format("%018d", qr[1].abs()).substring(0, 6);
    }

    static String shortAddress(String addr) {
        if (addr == null || addr.length() < 10) return addr;
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }

    static String shortHash(String hash) {
        if (hash == null || hash.length() < 16) return hash;
        return hash.substring(0, 10) + "..." + hash.substring(hash.length() - 6);
    }
}

// ─── Cache for deals (in-memory) ─────────────────────────────────────────────

final class ClawDealCache {
    private final Map<String, ClawDeal> byId = new ConcurrentHashMap<>();
    private final List<ClawDeal> all = new CopyOnWriteArrayList<>();
    private final int maxSize;
    private long lastRefresh;

    ClawDealCache(int maxSize) { this.maxSize = maxSize; }

    void put(ClawDeal d) {
        byId.put(d.dealId, d);
        all.removeIf(x -> x.dealId.equals(d.dealId));
        all.add(0, d);
        while (all.size() > maxSize) {
            ClawDeal removed = all.remove(all.size() - 1);
            byId.remove(removed.dealId);
        }
    }

    void putAll(List<ClawDeal> list) {
        for (ClawDeal d : list) put(d);
    }

    ClawDeal get(String dealId) { return byId.get(dealId); }

    List<ClawDeal> list(ClawDealFilter filter, int limit) {
        return all.stream()
            .filter(d -> filter == null || filter.matches(d))
            .limit(limit)
            .collect(Collectors.toList());
    }

    void setLastRefresh(long t) { lastRefresh = t; }
    long getLastRefresh() { return lastRefresh; }
    int size() { return all.size(); }
}

// ─── API request/response DTOs (for REST if used) ────────────────────────────

final class ClawOpenDealRequest {
    final String taker;
    final String amountWei;
    final long settleDelayBlocks;
    final String payloadHash;

    ClawOpenDealRequest(String taker, String amountWei, long settleDelayBlocks, String payloadHash) {
        this.taker = taker;
        this.amountWei = amountWei;
        this.settleDelayBlocks = settleDelayBlocks;
        this.payloadHash = payloadHash;
    }
}

final class ClawSettleDealRequest {
    final String dealId;
    final String makerAmountWei;
    final String takerAmountWei;

    ClawSettleDealRequest(String dealId, String makerAmountWei, String takerAmountWei) {
        this.dealId = dealId;
        this.makerAmountWei = makerAmountWei;
        this.takerAmountWei = takerAmountWei;
    }
}

final class ClawPostRequest {
    final String contentHash;
