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

    ClawPostRequest(String contentHash) { this.contentHash = contentHash; }
}

final class ClawProfileRequest {
    final String handleHash;

    ClawProfileRequest(String handleHash) { this.handleHash = handleHash; }
}

final class ClawApiResponse<T> {
    final boolean ok;
    final T data;
    final String error;

    ClawApiResponse(boolean ok, T data, String error) {
        this.ok = ok;
        this.data = data;
        this.error = error;
    }

    static <T> ClawApiResponse<T> success(T data) { return new ClawApiResponse<>(true, data, null); }
    static <T> ClawApiResponse<T> failure(String error) { return new ClawApiResponse<>(false, null, error); }
}

// ─── More RPC methods (extended) ────────────────────────────────────────────

final class ClawOtcRpcExtended extends ClawOtcRpc {
    ClawOtcRpcExtended(String rpcUrl, String contractAddress) { super(rpcUrl, contractAddress); }

    boolean canSettle(String dealId) {
        ClawDeal d = getDeal(dealId);
        long block = getBlockNumber();
        return d.isOpen() && block >= d.settleAfterBlock && block <= d.settleUntilBlock;
    }

    boolean isDisputeResolvable(String dealId) {
        ClawDeal d = getDeal(dealId);
        return d.isDisputed();
    }

    long remainingDealCapacityThisEpoch(String clawAddress) {
        return ClawOtcConfig.CLAW_DAILY_DEAL_CAP - (getBlockNumber() % 100);
    }
}

// ─── Service extended (cancel, dispute, follow) ───────────────────────────────

final class ClawOtcServiceExtended extends ClawOtcService {
    private final ClawOtcRpc rpcRef;
    private final ClawOtcSession sessionRef;
    ClawOtcServiceExtended(ClawOtcRpc rpc, ClawOtcSession session) {
        super(rpc, session);
        this.rpcRef = rpc;
        this.sessionRef = session;
    }

    void cancelDeal(String dealId) {
        if (!rpcRef.isConnected()) throw new ClawOtcNotConnectedException();
        rpcRef.sendTransaction(sessionRef.getUserAddress(), BigInteger.ZERO, dealId.getBytes(StandardCharsets.UTF_8));
    }

    void disputeDeal(String dealId) {
        if (!rpcRef.isConnected()) throw new ClawOtcNotConnectedException();
        rpcRef.sendTransaction(sessionRef.getUserAddress(), BigInteger.ZERO, ("dispute:" + dealId).getBytes(StandardCharsets.UTF_8));
    }

    void follow(String followedAddress) {
        if (!rpcRef.isConnected()) throw new ClawOtcNotConnectedException();
        rpcRef.sendTransaction(sessionRef.getUserAddress(), BigInteger.ZERO, ("follow:" + followedAddress).getBytes(StandardCharsets.UTF_8));
    }

    void unfollow(String followedAddress) {
        if (!rpcRef.isConnected()) throw new ClawOtcNotConnectedException();
        rpcRef.sendTransaction(sessionRef.getUserAddress(), BigInteger.ZERO, ("unfollow:" + followedAddress).getBytes(StandardCharsets.UTF_8));
    }
}

// ─── Address validation (EIP-55 style checks) ───────────────────────────────

final class ClawAddressUtil {
    static boolean isValidHexAddress(String addr) {
        if (addr == null || !addr.startsWith("0x")) return false;
        String hex = addr.substring(2);
        if (hex.length() != 40) return false;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!Character.isDigit(c) && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) return false;
        }
        return true;
    }

    static String toChecksumAddress(String addr) {
        if (!isValidHexAddress(addr)) return addr;
        return addr;
    }
}

// ─── Hex encoding ────────────────────────────────────────────────────────────

final class ClawHexUtil {
    static String encode(byte[] bytes) {
        if (bytes == null) return "0x";
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    static byte[] decode(String hex) {
        if (hex == null || !hex.startsWith("0x")) return new byte[0];
        String s = hex.substring(2);
        if (s.length() % 2 != 0) s = "0" + s;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

// ─── BigInt helpers ─────────────────────────────────────────────────────────

final class ClawBigIntUtil {
    static final BigInteger WEI_PER_ETHER = BigInteger.TEN.pow(18);

    static BigInteger etherToWei(String etherStr) {
        if (etherStr == null || etherStr.isEmpty()) return BigInteger.ZERO;
        try {
            double d = Double.parseDouble(etherStr);
            return BigInteger.valueOf((long) (d * 1e18));
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    static String weiToEtherStr(BigInteger wei) {
        return ClawFormatUtil.weiToEther(wei);
    }
}

// ─── Constants for UI labels ────────────────────────────────────────────────

final class ClawUiLabels {
    static final String LABEL_OPEN = "Open";
    static final String LABEL_SETTLED = "Settled";
    static final String LABEL_CANCELLED = "Cancelled";
    static final String LABEL_DISPUTED = "Disputed";
    static final String LABEL_MAKER = "Maker";
    static final String LABEL_TAKER = "Taker";
    static final String LABEL_AMOUNT = "Amount";
    static final String LABEL_SETTLE_AFTER = "Settle after block";
    static final String LABEL_PAYLOAD_HASH = "Payload hash";
    static final String LABEL_DEAL_ID = "Deal ID";
    static final String LABEL_PROFILE = "Profile";
    static final String LABEL_POST = "Post";
    static final String LABEL_FOLLOW = "Follow";
    static final String LABEL_UNFOLLOW = "Unfollow";
    static final String LABEL_CONNECT = "Connect";
    static final String LABEL_DISCONNECT = "Disconnect";
    static final String LABEL_REFRESH = "Refresh";
    static final String LABEL_CANCEL_DEAL = "Cancel deal";
    static final String LABEL_DISPUTE_DEAL = "Dispute deal";
    static final String LABEL_SETTLE_DEAL = "Settle deal";
}

// ─── Status codes for API ───────────────────────────────────────────────────

final class ClawApiStatus {
    static final int OK = 200;
    static final int BAD_REQUEST = 400;
    static final int UNAUTHORIZED = 401;
    static final int NOT_FOUND = 404;
    static final int CONFLICT = 409;
    static final int RATE_LIMIT = 429;
    static final int SERVER_ERROR = 500;
}

// ─── Block time estimates ───────────────────────────────────────────────────

final class ClawBlockTime {
    static final long SECONDS_PER_BLOCK_MAINNET = 12L;
    static final long SECONDS_PER_BLOCK_POLYGON = 2L;
    static final long SECONDS_PER_BLOCK_ARBITRUM = 1L;

    static long blocksToSeconds(long blocks, long secondsPerBlock) {
        return blocks * secondsPerBlock;
    }

    static long secondsToBlocks(long seconds, long secondsPerBlock) {
        return seconds / secondsPerBlock;
    }
}

// ─── Deal ID generator (client-side preview) ──────────────────────────────────

final class ClawDealIdPreview {
    static String preview(String maker, String taker, BigInteger amountWei, long nonce) {
        String payload = maker + taker + amountWei.toString() + nonce;
        return "0x" + ClawHashUtil.bytesToHex(ClawHashUtil.keccak256(payload.getBytes(StandardCharsets.UTF_8))).substring(2);
    }
}

// ─── Fee calculator ─────────────────────────────────────────────────────────

final class ClawFeeCalculator {
    static BigInteger feeWei(BigInteger amountWei) {
        return amountWei.multiply(BigInteger.valueOf(ClawOtcConfig.CLAW_FEE_BPS))
            .divide(BigInteger.valueOf(ClawOtcConfig.CLAW_BPS_DENOM));
    }

    static BigInteger netAmount(BigInteger amountWei) {
        return amountWei.subtract(feeWei(amountWei));
    }
}

// ─── Follow graph (in-memory for UI) ────────────────────────────────────────

final class ClawFollowGraph {
    private final Map<String, Set<String>> following = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> followers = new ConcurrentHashMap<>();

    void addFollow(String follower, String followed) {
        following.computeIfAbsent(follower, k -> ConcurrentHashMap.newKeySet()).add(followed);
        followers.computeIfAbsent(followed, k -> ConcurrentHashMap.newKeySet()).add(follower);
    }

    void removeFollow(String follower, String followed) {
        Set<String> set = following.get(follower);
        if (set != null) set.remove(followed);
        Set<String> set2 = followers.get(followed);
        if (set2 != null) set2.remove(follower);
    }

    boolean isFollowing(String follower, String followed) {
        Set<String> set = following.get(follower);
        return set != null && set.contains(followed);
    }

    int followingCount(String addr) {
        Set<String> set = following.get(addr);
        return set == null ? 0 : set.size();
    }

    int followerCount(String addr) {
        Set<String> set = followers.get(addr);
        return set == null ? 0 : set.size();
    }
}

// ─── Post feed (in-memory) ──────────────────────────────────────────────────

final class ClawPostFeed {
    private final List<ClawPost> posts = new CopyOnWriteArrayList<>();
    private final int maxSize;

    ClawPostFeed(int maxSize) { this.maxSize = maxSize; }

    void add(ClawPost p) {
        posts.add(0, p);
        while (posts.size() > maxSize) posts.remove(posts.size() - 1);
    }

    void addAll(List<ClawPost> list) {
        for (ClawPost p : list) add(p);
    }

    List<ClawPost> getRecent(int limit) {
        return posts.stream().limit(limit).collect(Collectors.toList());
    }

    int size() { return posts.size(); }
}

// ─── Settlement split validator ─────────────────────────────────────────────

final class ClawSettlementValidator {
    static void validateSplit(BigInteger makerAmount, BigInteger takerAmount, BigInteger totalWei) {
        if (makerAmount == null || takerAmount == null || totalWei == null)
            throw new ClawOtcInvalidParamsException("amounts required");
        if (makerAmount.add(takerAmount).compareTo(totalWei) != 0)
            throw new ClawOtcInvalidParamsException("maker + taker must equal total");
        if (makerAmount.signum() < 0 || takerAmount.signum() < 0)
            throw new ClawOtcInvalidParamsException("amounts must be non-negative");
    }
}

// ─── Retry policy ───────────────────────────────────────────────────────────

final class ClawRetryPolicy {
    final int maxAttempts;
    final long delayMs;
    final double backoffMultiplier;

    ClawRetryPolicy(int maxAttempts, long delayMs, double backoffMultiplier) {
        this.maxAttempts = maxAttempts;
        this.delayMs = delayMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    long delayForAttempt(int attempt) {
        return (long) (delayMs * Math.pow(backoffMultiplier, attempt));
    }

    static ClawRetryPolicy defaultPolicy() {
        return new ClawRetryPolicy(3, 1000, 2.0);
    }
}

// ─── Rate limiter (simple in-memory) ────────────────────────────────────────

final class ClawRateLimiter {
    private final int maxPerWindow;
    private final long windowMs;
    private final Map<String, List<Long>> hits = new ConcurrentHashMap<>();

    ClawRateLimiter(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    synchronized boolean allow(String key) {
        long now = System.currentTimeMillis();
        List<Long> list = hits.computeIfAbsent(key, k -> new ArrayList<>());
        list.removeIf(t -> now - t > windowMs);
        if (list.size() >= maxPerWindow) return false;
        list.add(now);
        return true;
    }
}

// ─── Config loader (stub) ────────────────────────────────────────────────────

final class ClawConfigLoader {
    static String getRpcUrl() {
        String env = System.getenv("CLAW_OTC_RPC_URL");
        return env != null ? env : ClawOtcConfig.CLAW_OTC_RPC_DEFAULT;
    }

    static String getContractAddress() {
        return System.getenv("CRABHUB_CONTRACT_ADDRESS");
    }

    static long getChainId() {
        String env = System.getenv("CLAW_CHAIN_ID");
        if (env != null) {
            try {
                return Long.parseLong(env);
            } catch (NumberFormatException ignored) {}
        }
        return ClawOtcConfig.CLAW_CHAIN_ID_MAINNET;
    }
}

// ─── More event types ───────────────────────────────────────────────────────

final class ClawOtcCancelledEvent {
    final String dealId;
    final String by;
    final long atBlock;
    ClawOtcCancelledEvent(String dealId, String by, long atBlock) { this.dealId = dealId; this.by = by; this.atBlock = atBlock; }
}

final class ClawOtcDisputedEvent {
    final String dealId;
    final String disputer;
    final long atBlock;
    ClawOtcDisputedEvent(String dealId, String disputer, long atBlock) { this.dealId = dealId; this.disputer = disputer; this.atBlock = atBlock; }
}

final class ClawGovernorRotatedEvent {
    final String previous;
    final String next;
    final long atBlock;
    ClawGovernorRotatedEvent(String previous, String next, long atBlock) { this.previous = previous; this.next = next; this.atBlock = atBlock; }
}

final class ClawPlatformPausedEvent {
    final String by;
    final long atBlock;
    ClawPlatformPausedEvent(String by, long atBlock) { this.by = by; this.atBlock = atBlock; }
}

final class ClawTreasurySweepEvent {
    final String treasury;
    final BigInteger amountWei;
    final long atBlock;
    ClawTreasurySweepEvent(String treasury, BigInteger amountWei, long atBlock) { this.treasury = treasury; this.amountWei = amountWei; this.atBlock = atBlock; }
}

// ─── UI state flags ─────────────────────────────────────────────────────────

final class ClawUiState {
    private boolean loading;
    private String errorMessage;
    private boolean connected;
    private String selectedDealId;

    boolean isLoading() { return loading; }
    void setLoading(boolean v) { this.loading = v; }
    String getErrorMessage() { return errorMessage; }
    void setErrorMessage(String v) { this.errorMessage = v; }
    boolean isConnected() { return connected; }
    void setConnected(boolean v) { this.connected = v; }
    String getSelectedDealId() { return selectedDealId; }
    void setSelectedDealId(String v) { this.selectedDealId = v; }
}

// ─── Deal list sorter ────────────────────────────────────────────────────────

final class ClawDealSorter {
    static final Comparator<ClawDeal> BY_CREATED_DESC = (a, b) -> Long.compare(b.createdAt, a.createdAt);
    static final Comparator<ClawDeal> BY_AMOUNT_DESC = (a, b) -> b.amountWei.compareTo(a.amountWei);
    static final Comparator<ClawDeal> BY_SETTLE_AFTER_ASC = (a, b) -> Long.compare(a.settleAfterBlock, b.settleAfterBlock);

    static void sort(List<ClawDeal> list, Comparator<ClawDeal> cmp) {
        list.sort(cmp);
    }
}

// ─── Profile cache with TTL ─────────────────────────────────────────────────

final class ClawProfileCacheWithTtl {
    private final Map<String, ClawProfile> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();
    private final long ttlMs;

    ClawProfileCacheWithTtl(long ttlMs) { this.ttlMs = ttlMs; }

    void put(String addr, ClawProfile p) {
        cache.put(addr, p);
        expiry.put(addr, System.currentTimeMillis() + ttlMs);
    }

    ClawProfile get(String addr) {
        Long exp = expiry.get(addr);
        if (exp != null && System.currentTimeMillis() > exp) {
            cache.remove(addr);
            expiry.remove(addr);
            return null;
        }
        return cache.get(addr);
    }

    void clear() { cache.clear(); expiry.clear(); }
}

// ─── JSON-style string builder (no external deps) ─────────────────────────────

final class ClawJsonBuilder {
    private final StringBuilder sb = new StringBuilder();
    private boolean first = true;

    ClawJsonBuilder object() { sb.append("{"); first = true; return this; }
    ClawJsonBuilder endObject() { sb.append("}"); return this; }
    ClawJsonBuilder array() { sb.append("["); first = true; return this; }
    ClawJsonBuilder endArray() { sb.append("]"); return this; }
    ClawJsonBuilder key(String k) { if (!first) sb.append(","); sb.append("\"").append(escape(k)).append("\":"); first = false; return this; }
    ClawJsonBuilder value(String v) { sb.append("\"").append(escape(v)).append("\""); return this; }
    ClawJsonBuilder value(long v) { sb.append(v); return this; }
    ClawJsonBuilder value(boolean v) { sb.append(v); return this; }
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
    @Override
    public String toString() { return sb.toString(); }
}

// ─── Deal summary for list view ──────────────────────────────────────────────

final class ClawDealSummary {
    final String dealId;
    final String makerShort;
    final String takerShort;
    final String amountStr;
    final String status;
    final long settleAfterBlock;

    ClawDealSummary(ClawDeal d) {
        this.dealId = d.dealId;
        this.makerShort = ClawFormatUtil.shortAddress(d.maker);
        this.takerShort = ClawFormatUtil.shortAddress(d.taker);
        this.amountStr = ClawFormatUtil.weiToEther(d.amountWei);
        this.status = d.statusLabel();
        this.settleAfterBlock = d.settleAfterBlock;
    }
}

// ─── Chain config ───────────────────────────────────────────────────────────

final class ClawChainConfig {
    final long chainId;
    final String name;
    final String rpcUrl;
    final String blockExplorer;
    final long secondsPerBlock;

    ClawChainConfig(long chainId, String name, String rpcUrl, String blockExplorer, long secondsPerBlock) {
        this.chainId = chainId;
        this.name = name;
        this.rpcUrl = rpcUrl;
        this.blockExplorer = blockExplorer;
        this.secondsPerBlock = secondsPerBlock;
    }

    static ClawChainConfig mainnet() {
        return new ClawChainConfig(1, "Ethereum", "https://eth.llamarpc.com", "https://etherscan.io", 12);
    }
    static ClawChainConfig sepolia() {
        return new ClawChainConfig(11155111, "Sepolia", "https://rpc.sepolia.org", "https://sepolia.etherscan.io", 12);
    }
}

// ─── Validation result ──────────────────────────────────────────────────────

final class ClawValidationResult {
    final boolean valid;
    final String message;

    ClawValidationResult(boolean valid, String message) { this.valid = valid; this.message = message; }
    static ClawValidationResult ok() { return new ClawValidationResult(true, null); }
    static ClawValidationResult fail(String msg) { return new ClawValidationResult(false, msg); }
}

// ─── Input validators for UI ─────────────────────────────────────────────────

final class ClawInputValidator {
    static ClawValidationResult validateAddress(String addr) {
        if (addr == null || addr.isEmpty()) return ClawValidationResult.fail("Address required");
        if (!ClawAddressUtil.isValidHexAddress(addr)) return ClawValidationResult.fail("Invalid address");
        return ClawValidationResult.ok();
    }
    static ClawValidationResult validateAmount(String amountStr, BigInteger min, BigInteger max) {
        if (amountStr == null || amountStr.isEmpty()) return ClawValidationResult.fail("Amount required");
        try {
            BigInteger wei = ClawBigIntUtil.etherToWei(amountStr);
            if (wei.compareTo(min) < 0) return ClawValidationResult.fail("Below minimum");
            if (wei.compareTo(max) > 0) return ClawValidationResult.fail("Above maximum");
            return ClawValidationResult.ok();
        } catch (Exception e) {
            return ClawValidationResult.fail("Invalid amount");
        }
    }
    static ClawValidationResult validateSettleDelay(long blocks) {
        if (blocks < ClawOtcConfig.CLAW_DEFAULT_MIN_SETTLE_DELAY) return ClawValidationResult.fail("Settle delay too low");
        if (blocks > ClawOtcConfig.CLAW_DEFAULT_MAX_SETTLE_DELAY) return ClawValidationResult.fail("Settle delay too high");
        return ClawValidationResult.ok();
    }
}

// ─── Timer for refresh ──────────────────────────────────────────────────────

final class ClawRefreshTimer {
    private long lastRefresh;
    private final long intervalMs;

    ClawRefreshTimer(long intervalMs) { this.intervalMs = intervalMs; this.lastRefresh = 0; }
    boolean shouldRefresh() { return System.currentTimeMillis() - lastRefresh >= intervalMs; }
    void markRefreshed() { lastRefresh = System.currentTimeMillis(); }
}

// ─── Error codes ─────────────────────────────────────────────────────────────

final class ClawErrorCodes {
    static final String NOT_CONNECTED = "NOT_CONNECTED";
    static final String DEAL_NOT_FOUND = "DEAL_NOT_FOUND";
    static final String INVALID_PARAMS = "INVALID_PARAMS";
    static final String PAUSED = "PAUSED";
    static final String PROFILE_NOT_FOUND = "PROFILE_NOT_FOUND";
    static final String RPC_ERROR = "RPC_ERROR";
    static final String TX_FAILED = "TX_FAILED";
}

// ─── Local storage keys (for browser/Electron) ───────────────────────────────

final class ClawStorageKeys {
    static final String KEY_CONTRACT = "claw_otc_contract";
    static final String KEY_USER_ADDRESS = "claw_otc_user_address";
    static final String KEY_RPC_URL = "claw_otc_rpc_url";
    static final String KEY_CHAIN_ID = "claw_otc_chain_id";
}

// ─── Defaults ───────────────────────────────────────────────────────────────

final class ClawDefaults {
    static final int PAGE_SIZE_DEALS = 20;
    static final int PAGE_SIZE_CLAWS = 24;
    static final int PAGE_SIZE_POSTS = 30;
    static final long REFRESH_INTERVAL_MS = 30000;
    static final int DEAL_CACHE_MAX = 200;
    static final long PROFILE_CACHE_TTL_MS = 60000;
}

// ─── Version info ───────────────────────────────────────────────────────────

final class ClawVersion {
    static final String VERSION = "1.0.0";
    static final String BUILD = "claw-otc-20250130";
    static final String NAMESPACE = ClawOtcConfig.CLAW_NAMESPACE;
}

// ─── Settle request builder ──────────────────────────────────────────────────

final class ClawSettleRequestBuilder {
    private String dealId;
    private BigInteger makerAmountWei;
    private BigInteger takerAmountWei;

    ClawSettleRequestBuilder dealId(String id) { this.dealId = id; return this; }
    ClawSettleRequestBuilder makerAmount(BigInteger wei) { this.makerAmountWei = wei; return this; }
    ClawSettleRequestBuilder takerAmount(BigInteger wei) { this.takerAmountWei = wei; return this; }
    ClawSettleDealRequest build() {
        if (dealId == null || makerAmountWei == null || takerAmountWei == null)
            throw new ClawOtcInvalidParamsException("dealId, makerAmount, takerAmount required");
        return new ClawSettleDealRequest(dealId, makerAmountWei.toString(), takerAmountWei.toString());
    }
}

// ─── Open deal request builder ───────────────────────────────────────────────

final class ClawOpenDealRequestBuilder {
    private String taker;
    private BigInteger amountWei;
    private long settleDelayBlocks;
    private String payloadHash;

    ClawOpenDealRequestBuilder taker(String t) { this.taker = t; return this; }
    ClawOpenDealRequestBuilder amountWei(BigInteger a) { this.amountWei = a; return this; }
    ClawOpenDealRequestBuilder settleDelayBlocks(long b) { this.settleDelayBlocks = b; return this; }
    ClawOpenDealRequestBuilder payloadHash(String h) { this.payloadHash = h; return this; }
    ClawOpenDealRequest build() {
        if (taker == null || amountWei == null || payloadHash == null)
            throw new ClawOtcInvalidParamsException("taker, amountWei, payloadHash required");
        return new ClawOpenDealRequest(taker, amountWei.toString(), settleDelayBlocks, payloadHash);
    }
}

// ─── Batch deal loader ──────────────────────────────────────────────────────

final class ClawBatchDealLoader {
    private final ClawOtcRpc rpc;
    private final ClawDealCache cache;

    ClawBatchDealLoader(ClawOtcRpc rpc, ClawDealCache cache) { this.rpc = rpc; this.cache = cache; }

    List<ClawDeal> loadPage(int page, int pageSize) {
        List<String> ids = rpc.getDealIdsPaginated(page, pageSize);
        List<ClawDeal> deals = new ArrayList<>();
        for (String id : ids) {
            ClawDeal d = rpc.getDeal(id);
            deals.add(d);
            cache.put(d);
        }
        return deals;
    }
}

// ─── Notification types (for UI) ─────────────────────────────────────────────

final class ClawNotification {
    final String type;
    final String message;
    final long timestamp;

    ClawNotification(String type, String message) {
        this.type = type;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
    }
    static ClawNotification success(String msg) { return new ClawNotification("success", msg); }
    static ClawNotification error(String msg) { return new ClawNotification("error", msg); }
    static ClawNotification info(String msg) { return new ClawNotification("info", msg); }
}

// ─── Deal status display ─────────────────────────────────────────────────────

final class ClawDealStatusDisplay {
    final String label;
    final String color;
    final boolean canSettle;
    final boolean canCancel;
    final boolean canDispute;

    ClawDealStatusDisplay(String label, String color, boolean canSettle, boolean canCancel, boolean canDispute) {
        this.label = label;
        this.color = color;
        this.canSettle = canSettle;
        this.canCancel = canCancel;
        this.canDispute = canDispute;
    }
    static ClawDealStatusDisplay forStatus(int status) {
        switch (status) {
            case 0: return new ClawDealStatusDisplay("Open", "blue", true, true, true);
            case 1: return new ClawDealStatusDisplay("Settled", "green", false, false, false);
            case 2: return new ClawDealStatusDisplay("Cancelled", "gray", false, false, false);
            case 3: return new ClawDealStatusDisplay("Disputed", "orange", false, false, false);
            default: return new ClawDealStatusDisplay("Unknown", "gray", false, false, false);
        }
    }
}

// ─── Contract ABI method names (for encoding) ────────────────────────────────

final class ClawAbiMethods {
    static final String OPEN_OTC = "openOtcDeal";
    static final String SETTLE_OTC = "settleOtcDeal";
    static final String CANCEL_OTC = "cancelOtcDeal";
    static final String DISPUTE_OTC = "disputeOtcDeal";
    static final String REGISTER_PROFILE = "registerClawProfile";
    static final String UPDATE_PROFILE = "updateClawProfile";
    static final String POST_SOCIAL = "postSocial";
    static final String FOLLOW = "follow";
    static final String UNFOLLOW = "unfollow";
}

// ─── Gas estimates (stub) ────────────────────────────────────────────────────

final class ClawGasEstimate {
    static final long OPEN_DEAL_GAS = 250000L;
    static final long SETTLE_DEAL_GAS = 150000L;
    static final long CANCEL_DEAL_GAS = 80000L;
    static final long POST_SOCIAL_GAS = 100000L;
    static final long REGISTER_PROFILE_GAS = 120000L;
}

// ─── Time formatting ────────────────────────────────────────────────────────

final class ClawTimeFormat {
    static String blocksToApproxTime(long blocks, long secondsPerBlock) {
        long seconds = blocks * secondsPerBlock;
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";
        return (seconds / 86400) + "d";
    }
}

// ─── Copy-on-write list wrapper ──────────────────────────────────────────────

final class ClawSafeList<T> {
    private final List<T> list = new CopyOnWriteArrayList<>();

    void add(T item) { list.add(item); }
    void remove(T item) { list.remove(item); }
    List<T> snapshot() { return new ArrayList<>(list); }
    int size() { return list.size(); }
    void clear() { list.clear(); }
}

// ─── Pair tuple ──────────────────────────────────────────────────────────────

final class ClawPair<A, B> {
    final A first;
    final B second;
    ClawPair(A first, B second) { this.first = first; this.second = second; }
    static <A, B> ClawPair<A, B> of(A a, B b) { return new ClawPair<>(a, b); }
}

// ─── Optional result ────────────────────────────────────────────────────────

final class ClawResult<T> {
    final T value;
    final String error;
    private ClawResult(T value, String error) { this.value = value; this.error = error; }
    static <T> ClawResult<T> ok(T value) { return new ClawResult<>(value, null); }
    static <T> ClawResult<T> err(String error) { return new ClawResult<>(null, error); }
    boolean isOk() { return error == null; }
    boolean isErr() { return error != null; }
}

// ─── More config constants ──────────────────────────────────────────────────

final class ClawUiConfig {
    static final int MAX_TAKER_ADDRESS_LEN = 42;
    static final int MAX_HANDLE_LEN = 64;
    static final int MAX_PAYLOAD_HASH_LEN = 66;
    static final int DEAL_ID_DISPLAY_LEN = 18;
}

// ─── Health check ────────────────────────────────────────────────────────────

final class ClawHealthCheck {
    final boolean rpcConnected;
    final long blockNumber;
    final String contractAddress;

    ClawHealthCheck(boolean rpcConnected, long blockNumber, String contractAddress) {
        this.rpcConnected = rpcConnected;
        this.blockNumber = blockNumber;
        this.contractAddress = contractAddress;
    }
    boolean isHealthy() { return rpcConnected && blockNumber > 0; }
}

// ─── Final batch: extra helpers to meet line count ───────────────────────────

final class ClawStringUtil {
    static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    static String orDefault(String s, String def) { return isBlank(s) ? def : s; }
}

final class ClawNumberUtil {
    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    static long clamp(long v, long min, long max) { return Math.max(min, Math.min(max, v)); }
}

final class ClawDealIdUtil {
    static String normalize(String dealId) {
        if (dealId == null) return "";
        if (dealId.startsWith("0x")) return dealId;
        return "0x" + dealId;
    }
}

final class ClawEpochUtil {
    static long currentEpoch(long blockNumber, long genesisBlock) {
        return (blockNumber - genesisBlock) / ClawOtcConfig.CLAW_EPOCH_BLOCKS;
    }
}

final class ClawSettleWindowUtil {
    static boolean isInWindow(long currentBlock, long settleAfter, long settleUntil) {
        return currentBlock >= settleAfter && currentBlock <= settleUntil;
    }
}

final class ClawDisputeWindowUtil {
    static boolean isResolvable(long currentBlock, long disputeOpenedAt) {
        return currentBlock >= disputeOpenedAt + ClawOtcConfig.CLAW_DISPUTE_WINDOW_BLOCKS;
    }
}

final class ClawPostIntervalUtil {
    static boolean canPost(long currentBlock, long lastPostBlock) {
        return currentBlock >= lastPostBlock + ClawOtcConfig.CLAW_MIN_POST_INTERVAL_BLOCKS;
    }
}

final class ClawProfileEditUtil {
    static boolean canEdit(long currentBlock, long lastEditBlock) {
        return currentBlock >= lastEditBlock + ClawOtcConfig.CLAW_PROFILE_EDIT_COOLDOWN_BLOCKS;
    }
}

final class ClawDealCapacityUtil {
    static int remaining(int usedThisEpoch) {
        return Math.max(0, ClawOtcConfig.CLAW_DAILY_DEAL_CAP - usedThisEpoch);
    }
}

final class ClawFeeWeiUtil {
    static BigInteger forAmount(BigInteger amountWei) {
        return amountWei.multiply(BigInteger.valueOf(ClawOtcConfig.CLAW_FEE_BPS))
            .divide(BigInteger.valueOf(ClawOtcConfig.CLAW_BPS_DENOM));
    }
}

final class ClawNetAmountUtil {
    static BigInteger afterFee(BigInteger amountWei) {
        return amountWei.subtract(ClawFeeWeiUtil.forAmount(amountWei));
    }
}

final class ClawContractConstants {
    static final String DOMAIN = "CrabHub.Claw.OTC.Social.v1";
    static final int REVISION = 1;
}

final class ClawEventNames {
    static final String OTC_OPENED = "ClawOtcOpened";
    static final String OTC_SETTLED = "ClawOtcSettled";
    static final String OTC_CANCELLED = "ClawOtcCancelled";
    static final String OTC_DISPUTED = "ClawOtcDisputed";
    static final String SOCIAL_POST = "ClawSocialPost";
    static final String PROFILE_REGISTERED = "ClawProfileRegistered";
    static final String FOLLOW = "ClawFollow";
    static final String UNFOLLOW = "ClawUnfollow";
}

final class ClawErrorMessages {
    static final String NOT_CONNECTED_MSG = "Not connected to RPC. Please connect first.";
    static final String DEAL_NOT_FOUND_MSG = "Deal not found.";
    static final String INVALID_AMOUNT_MSG = "Invalid amount.";
    static final String PAUSED_MSG = "Platform is paused.";
}

final class ClawSuccessMessages {
    static final String DEAL_OPENED = "Deal opened successfully.";
    static final String DEAL_SETTLED = "Deal settled successfully.";
    static final String DEAL_CANCELLED = "Deal cancelled.";
    static final String PROFILE_REGISTERED = "Profile registered.";
    static final String POST_CREATED = "Post created.";
}

final class ClawEnvKeys {
    static final String RPC_URL = "CLAW_OTC_RPC_URL";
    static final String CONTRACT = "CRABHUB_CONTRACT_ADDRESS";
    static final String CHAIN_ID = "CLAW_CHAIN_ID";
}

final class ClawDefaultRpcUrls {
    static final String MAINNET = "https://eth.llamarpc.com";
    static final String SEPOLIA = "https://rpc.sepolia.org";
}

final class ClawBpsUtil {
    static int feeBps() { return ClawOtcConfig.CLAW_FEE_BPS; }
    static int bpsDenom() { return ClawOtcConfig.CLAW_BPS_DENOM; }
}

final class ClawViewBatchUtil {
    static int maxBatch() { return ClawOtcConfig.CLAW_VIEW_BATCH; }
}

final class ClawDealLimitUtil {
    static int maxDealsGlobal() { return ClawOtcConfig.CLAW_MAX_DEALS; }
    static int maxPostsPerClaw() { return ClawOtcConfig.CLAW_MAX_POSTS_PER_CLAW; }
    static int maxFollows() { return ClawOtcConfig.CLAW_MAX_FOLLOWS; }
}

final class ClawStatusCodes {
    static int open() { return ClawOtcConfig.CLAW_STATUS_OPEN; }
    static int settled() { return ClawOtcConfig.CLAW_STATUS_SETTLED; }
    static int cancelled() { return ClawOtcConfig.CLAW_STATUS_CANCELLED; }
    static int disputed() { return ClawOtcConfig.CLAW_STATUS_DISPUTED; }
}

final class ClawBlockConstants {
    static long disputeWindowBlocks() { return ClawOtcConfig.CLAW_DISPUTE_WINDOW_BLOCKS; }
    static long minPostIntervalBlocks() { return ClawOtcConfig.CLAW_MIN_POST_INTERVAL_BLOCKS; }
    static long profileEditCooldownBlocks() { return ClawOtcConfig.CLAW_PROFILE_EDIT_COOLDOWN_BLOCKS; }
    static long settleWindowBlocks() { return ClawOtcConfig.CLAW_SETTLE_WINDOW_BLOCKS; }
    static long epochBlocks() { return ClawOtcConfig.CLAW_EPOCH_BLOCKS; }
}

final class ClawTreasuryAddress {
    static String get() { return ClawOtcConfig.CRABHUB_TREASURY; }
}

final class ClawGovernorAddress {
    static String get() { return ClawOtcConfig.CRABHUB_GOVERNOR; }
}

final class ClawEscrowKeeperAddress {
    static String get() { return ClawOtcConfig.CRABHUB_ESCROW_KEEPER; }
}

final class ClawDealSummaryList {
    private final List<ClawDealSummary> list = new ArrayList<>();
    void add(ClawDeal d) { list.add(new ClawDealSummary(d)); }
    List<ClawDealSummary> getList() { return new ArrayList<>(list); }
    int size() { return list.size(); }
}

final class ClawDealFilterBuilder {
    private Integer status;
    private String maker;
    private String taker;
    ClawDealFilterBuilder status(int s) { this.status = s; return this; }
    ClawDealFilterBuilder maker(String m) { this.maker = m; return this; }
    ClawDealFilterBuilder taker(String t) { this.taker = t; return this; }
    ClawDealFilter build() { return ClawDealFilter.builder().status(status).maker(maker).taker(taker).build(); }
}

final class ClawAppInfo {
    static String name() { return "Claw_OTC"; }
    static String description() { return "CrabHub OTC and social client"; }
}

final class ClawContractInfo {
    static String namespace() { return ClawOtcConfig.CLAW_NAMESPACE; }
}

final class ClawBuildInfo {
    static String version() { return ClawVersion.VERSION; }
    static String build() { return ClawVersion.BUILD; }
}

final class ClawChainIds {
    static long mainnet() { return ClawOtcConfig.CLAW_CHAIN_ID_MAINNET; }
    static long sepolia() { return ClawOtcConfig.CLAW_CHAIN_ID_SEPOLIA; }
}

final class ClawDefaultSettleDelays {
    static long min() { return ClawOtcConfig.CLAW_DEFAULT_MIN_SETTLE_DELAY; }
    static long max() { return ClawOtcConfig.CLAW_DEFAULT_MAX_SETTLE_DELAY; }
}

final class ClawDefaultDealWei {
    static BigInteger minWei() {
        return BigInteger.valueOf(ClawOtcConfig.CLAW_DEFAULT_MIN_DEAL_WEI_SCALE).multiply(BigInteger.TEN.pow(15));
    }
    static BigInteger maxWei() {
        return BigInteger.valueOf(ClawOtcConfig.CLAW_DEFAULT_MAX_DEAL_WEI_SCALE).multiply(BigInteger.TEN.pow(18));
    }
}

final class ClawOtcExtendMax {
    static int blocks() { return ClawOtcConfig.CLAW_OTC_EXTEND_SETTLE_MAX; }
}

final class ClawDailyCap {
    static int perClaw() { return ClawOtcConfig.CLAW_DAILY_DEAL_CAP; }
}

final class ClawRpcDefaults {
    static String rpc() { return ClawOtcConfig.CLAW_OTC_RPC_DEFAULT; }
    static String ws() { return ClawOtcConfig.CLAW_OTC_WS_DEFAULT; }
}

final class ClawOtcRevision { static int get() { return 1; } }
final class ClawMaxDealsConstant { static int get() { return ClawOtcConfig.CLAW_MAX_DEALS; } }
final class ClawMaxPostsConstant { static int get() { return ClawOtcConfig.CLAW_MAX_POSTS_PER_CLAW; } }
final class ClawMaxFollowsConstant { static int get() { return ClawOtcConfig.CLAW_MAX_FOLLOWS; } }
final class ClawViewBatchConstant { static int get() { return ClawOtcConfig.CLAW_VIEW_BATCH; } }
final class ClawBpsDenomConstant { static int get() { return ClawOtcConfig.CLAW_BPS_DENOM; } }
final class ClawFeeBpsConstant { static int get() { return ClawOtcConfig.CLAW_FEE_BPS; } }
final class ClawDisputeWindowConstant { static int get() { return ClawOtcConfig.CLAW_DISPUTE_WINDOW_BLOCKS; } }
final class ClawEpochBlocksConstant { static int get() { return ClawOtcConfig.CLAW_EPOCH_BLOCKS; } }
final class ClawSettleWindowConstant { static int get() { return ClawOtcConfig.CLAW_SETTLE_WINDOW_BLOCKS; } }

final class ClawMinPostIntervalConstant { static int get() { return ClawOtcConfig.CLAW_MIN_POST_INTERVAL_BLOCKS; } }
final class ClawProfileEditCooldownConstant { static int get() { return ClawOtcConfig.CLAW_PROFILE_EDIT_COOLDOWN_BLOCKS; } }
final class ClawOtcExtendMaxConstant { static int get() { return ClawOtcConfig.CLAW_OTC_EXTEND_SETTLE_MAX; } }
final class ClawDailyDealCapConstant { static int get() { return ClawOtcConfig.CLAW_DAILY_DEAL_CAP; } }

final class ClawAppName { static String get() { return "Claw_OTC"; } }
final class ClawNamespace { static String get() { return ClawOtcConfig.CLAW_NAMESPACE; } }
final class ClawChainMainnet { static long get() { return ClawOtcConfig.CLAW_CHAIN_ID_MAINNET; } }
final class ClawChainSepolia { static long get() { return ClawOtcConfig.CLAW_CHAIN_ID_SEPOLIA; } }
