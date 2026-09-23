package com.eonmux.excalibur.bench.retrieval;

import com.eonmux.excalibur.retrieval.fusion.FusedDocument;
import com.eonmux.excalibur.retrieval.fusion.ReciprocalRankFusion;
import com.eonmux.excalibur.retrieval.rank.RankedList;
import com.eonmux.excalibur.retrieval.rank.ScoredDocument;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reciprocal rank fusion over chunk names, for the strategies that add the graph to another one.
 *
 * <p>The arithmetic is {@link ReciprocalRankFusion}, the fusion a node's hybrid route runs, with
 * the node's own rank offset. What this adds is only the adaptation from names to the integer ids
 * that fusion works on. Ids are assigned in the order the rankings are given, and fusion breaks a
 * tie on the lower id, so a tie keeps the first ranking's order - which is why callers pass the
 * strategy being extended first and the graph second: adding the graph never reorders two chunks
 * it says nothing about.
 */
final class RankFusion {

    /** One signal's ranking, best first. */
    record Ranking(String signal, List<String> ids) {
        Ranking {
            if (signal == null || signal.isBlank()) {
                throw new IllegalArgumentException("signal must not be blank");
            }
            ids = List.copyOf(Objects.requireNonNull(ids, "ids must not be null"));
        }
    }

    private RankFusion() {
    }

    /**
     * Fuses rankings by rank.
     *
     * @param limit    Most names to return
     * @param rrfK     The rank offset
     * @param rankings The rankings, the one whose order should win ties first
     * @return The fused ranking, best first
     */
    static List<String> fuse(int limit, int rrfK, Ranking... rankings) {
        Map<String, Long> idOf = new HashMap<>();
        List<String> names = new ArrayList<>();
        List<RankedList> lists = new ArrayList<>(rankings.length);
        for (Ranking ranking : rankings) {
            List<ScoredDocument> documents = new ArrayList<>(ranking.ids().size());
            for (int position = 0; position < ranking.ids().size(); position++) {
                String name = ranking.ids().get(position);
                long id = idOf.computeIfAbsent(name, n -> {
                    names.add(n);
                    return (long) names.size() - 1;
                });
                // Fusion reads the position, not the score; a descending score only keeps the
                // list self-consistent for anything that does look at it.
                documents.add(new ScoredDocument(id, ranking.ids().size() - position));
            }
            lists.add(new RankedList(ranking.signal(), documents));
        }
        List<FusedDocument> fused = new ReciprocalRankFusion(rrfK).fuse(lists, limit);
        List<String> out = new ArrayList<>(fused.size());
        for (FusedDocument document : fused) {
            out.add(names.get((int) document.documentId()));
        }
        return List.copyOf(out);
    }
}
