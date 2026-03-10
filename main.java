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

