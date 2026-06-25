package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.Bounds;
import com.lxb.server.cortex.LocatorSemantics;
import com.lxb.server.cortex.Util;
import com.lxb.server.cortex.dump.DumpActionsParser;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuntimeLocatorBuilder {
    private RuntimeLocatorBuilder() {}

    public static Map<String, Object> buildLocator(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> nodes
    ) {
        return buildNodeLocator(x, y, nodes);
    }

    private static Map<String, Object> buildNodeLocator(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> actionNodes
    ) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        List<LocatorSemantics.NodeRecord> nodes = LocatorSemantics.fromActionNodes(actionNodes);
        LocatorSemantics.NodeRecord best = LocatorSemantics.findSmallestContaining(x, y, nodes, 0);
        if (best == null) {
            best = LocatorSemantics.findSmallestContaining(x, y, nodes, 20);
        }
        if (best == null) {
            out.put("bounds_hint", Arrays.asList(x, y, x, y));
            return out;
        }

        Bounds b = best.bounds;
        putIfNotEmpty(out, "resource_id", informativeRidOrEmpty(best.resourceId));
        putIfNotEmpty(out, "text", best.text);
        putIfNotEmpty(out, "content_desc", best.contentDesc);
        putIfNotEmpty(out, "class", best.className);

        int peerCount = 1;
        Integer locatorIndex = null;
        Integer locatorCount = null;
        boolean useTextIdentity = false;
        boolean useParentIdentity = false;

        List<LocatorSemantics.NodeRecord> selfPeers =
                LocatorSemantics.findPeerCandidates(best, nodes, false, false);
        if (!selfPeers.isEmpty()) {
            peerCount = selfPeers.size();
        }

        List<LocatorSemantics.NodeRecord> basePeers = selfPeers;
        if (!best.text.isEmpty() && peerCount > 1) {
            List<LocatorSemantics.NodeRecord> textPeers =
                    LocatorSemantics.findPeerCandidates(best, nodes, false, true);
            if (!textPeers.isEmpty()) {
                basePeers = textPeers;
                peerCount = textPeers.size();
                useTextIdentity = true;
            }
        }

        List<LocatorSemantics.NodeRecord> parentPeers = basePeers;
        if (!best.parentRid.isEmpty() && peerCount > 1) {
            List<LocatorSemantics.NodeRecord> filtered =
                    LocatorSemantics.filterByParentRid(basePeers, best.parentRid);
            if (!filtered.isEmpty()) {
                parentPeers = filtered;
                peerCount = filtered.size();
                useParentIdentity = true;
            }
        }

        if (peerCount >= 2 && peerCount <= 3) {
            int[] indexCount = LocatorSemantics.findPeerIndex(best, parentPeers);
            if (indexCount != null) {
                locatorIndex = indexCount[0];
                locatorCount = indexCount[1];
            }
        }

        if (useParentIdentity) {
            putIfNotEmpty(out, "parent_rid", informativeRidOrEmpty(best.parentRid));
        }
        if (locatorIndex != null && locatorCount != null) {
            out.put("locator_index", locatorIndex);
            out.put("locator_count", locatorCount);
        }
        out.put("bounds_hint", Arrays.asList(b.left, b.top, b.right, b.bottom));
        out.put("locator_peer_count", peerCount);
        out.put("locator_identity_mode", identityMode(useTextIdentity, useParentIdentity, locatorIndex != null));
        return out;
    }

    private static String informativeRidOrEmpty(String rid) {
        String normalized = Util.normalizeResourceId(rid);
        return Util.isInformativeResourceId(normalized) ? normalized : "";
    }

    private static void putIfNotEmpty(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    private static String identityMode(boolean useText, boolean useParent, boolean useIndex) {
        StringBuilder sb = new StringBuilder("self_no_text");
        if (useText) sb.append("+text");
        if (useParent) sb.append("+parent");
        if (useIndex) sb.append("+index");
        return sb.toString();
    }
}
