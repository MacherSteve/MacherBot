package de.pvptrainer;

import java.util.*;

/** Descriptive single-setup statistics. No human reference or tier threshold is assumed. */
final class BotStatistics {
    private static final List<String> FAMILIES = List.of("MELEE","WHIFF","MACE","BOW","CRYSTAL","WIND","HEAL","CROSSBOW","PEARL","ANCHOR","POTION","REPAIR","GLIDE","ROCKET","WEB","WATER","LAVA","BLOCK","MINE","SHIELD","SWORD_REENTRY","DRAIN","SWORD_BACK_OUT","SWORD_HIT_SELECT","SWORD_JUMP_RESET");
    private final Map<String,Integer> openings = new TreeMap<>();
    private final Map<String,Map<String,Integer>> transitions = new TreeMap<>();
    private final Map<String,Map<String,List<Integer>>> gaps = new HashMap<>();
    private final Map<String,Integer> following = new TreeMap<>();
    private final List<Integer> latency = new ArrayList<>();
    final List<RoundTime> perRound = new ArrayList<>();
    int rounds, partial, neutral, missed, censored, openingCount, testCases, testRounds, correct, baselineCorrect;
    int worstRound, worstStart, longest = -1;
    record Cadence(int ticks, int support, int tied) {}
    record Pattern(String from, String to, int count, int base, double gap, Cadence cadence) {}
    record RoundTime(double median, int n) {}
    record Opening(String action,int tick) {}
    final Opening timedOpening;
    final int timedCount;

    BotStatistics(List<BotArchive.Round> all) {
        var starts=new LinkedHashMap<Opening,Integer>();
        for (int pos = 0; pos < all.size(); pos++) {
            var round = all.get(pos);
            if (round.outcome().equals("UNSCORED")) neutral++;
            if (round.data().truncated()) {partial++; perRound.add(new RoundTime(Double.NaN,0)); continue;}
            var roundLatency = new ArrayList<Integer>();
            for (var window : round.data().windows()) {
                if (window.hit() >= 0) {
                    int delay = window.hit() - window.start();
                    latency.add(delay);
                    roundLatency.add(delay);
                    if (delay > longest) {longest = delay; worstRound=pos+1; worstStart=window.start();}
                } else if (window.end() >= 0) missed++;
                else censored++;
            }
            Collections.sort(roundLatency);
            perRound.add(new RoundTime(medianSorted(roundLatency),roundLatency.size()));
            var actions = round.data().actions();
            if (!actions.isEmpty() && isolated(actions,0) && FAMILIES.contains(actions.getFirst().kind())) {
                increment(openings, actions.getFirst().kind());
                starts.merge(new Opening(actions.getFirst().kind(),actions.getFirst().tick()),1,Integer::sum);
                openingCount++;
            }
            // Freeze both models for the entire held-out round. Consecutive rounds never form a transition.
            boolean predictedThisRound = false;
            String baseline = winner(following);
            var predictions = new HashMap<String,String>();
            if (rounds>=3) transitions.forEach((from,counts)->{
                if(total(counts)>=5) predictions.put(from,winner(counts));
            });
            for (int i = 1; i < actions.size(); i++) {
                String from = actions.get(i-1).kind(), to = actions.get(i).kind();
                if (!isolated(actions,i-1) || !isolated(actions,i) || !FAMILIES.contains(to) || !predictions.containsKey(from)) continue;
                if (to.equals(predictions.get(from))) correct++;
                if (to.equals(baseline)) baselineCorrect++;
                testCases++;
                predictedThisRound = true;
            }
            if (predictedThisRound) testRounds++;
            for (int i = 1; i < actions.size(); i++) {
                var prior = actions.get(i-1);
                var next = actions.get(i);
                if (!isolated(actions,i-1) || !isolated(actions,i) || !FAMILIES.contains(prior.kind()) || !FAMILIES.contains(next.kind())) continue;
                increment(transitions.computeIfAbsent(prior.kind(), k -> new TreeMap<>()), next.kind());
                increment(following,next.kind());
                gaps.computeIfAbsent(prior.kind(), k -> new HashMap<>()).computeIfAbsent(next.kind(), k -> new ArrayList<>())
                    .add(next.tick()-prior.tick());
            }
            rounds++;
        }
        Collections.sort(latency);
        timedOpening=starts.keySet().stream().sorted(Comparator.<Opening>comparingInt(starts::get).reversed()
                .thenComparingInt(Opening::tick).thenComparing(Opening::action)).findFirst().orElse(null);
        timedCount=timedOpening==null?0:starts.get(timedOpening);
    }
    private static boolean isolated(List<BotTelemetry.Action> actions,int i) {
        int tick=actions.get(i).tick();
        return (i==0 || actions.get(i-1).tick()!=tick) && (i+1==actions.size() || actions.get(i+1).tick()!=tick);
    }
    private static void increment(Map<String,Integer> map,String key) {map.merge(key,1,Integer::sum);}
    private static int total(Map<String,Integer> counts){return counts.values().stream().mapToInt(Integer::intValue).sum();}
    private static String winner(Map<String,Integer> counts) {
        // Fixed alphabetical tie break in both predictors.
        return counts.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()
            .thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).findFirst().orElse("");
    }
    int hits(){return latency.size();}
    int windows(){return hits()+missed+censored;}
    double median(){return medianSorted(latency);}
    int p90(){return hits()<10 ? -1 : latency.get((int)Math.ceil(.9*hits())-1);}
    private static double medianSorted(List<Integer> sorted) {
        int n=sorted.size();
        return n==0 ? Double.NaN : (sorted.get((n-1)/2)+(double)sorted.get(n/2))/2;
    }
    Pattern opening(){
        String top=winner(openings);
        return new Pattern("",top,openings.getOrDefault(top,0),openingCount,Double.NaN,null);
    }
    List<Pattern> patterns(){
        var rows = new ArrayList<Pattern>();
        transitions.forEach((from,counts)->{
            String to = winner(counts);
            List<Integer> times = new ArrayList<>(gaps.get(from).get(to));
            Collections.sort(times);
            rows.add(new Pattern(from,to,counts.get(to),total(counts),medianSorted(times),cadence(times)));
        });
        rows.sort(Comparator.comparingInt(Pattern::base).reversed().thenComparing(Pattern::from));
        return rows;
    }
    private static Cadence cadence(List<Integer> sorted) {
        if (sorted.size() < 5) return null;
        int ticks = 0, support = 0, tied = 0;
        for (int start = 0; start < sorted.size();) {
            int end = start + 1;
            while (end < sorted.size() && sorted.get(end).equals(sorted.get(start))) end++;
            int n = end - start;
            if (n > support) {
                ticks = sorted.get(start);
                support = n;
                tied = 1;
            } else if (n == support) tied++;
            start = end;
        }
        return new Cadence(ticks, support, tied);
    }
    static String action(String kind){
        return switch(kind) {
            case "BREACH_SWAP" -> "Breach-Swap"; case "WIND_BURST_SMASH" -> "Wind-Burst-Smash";
            case "STUN_SLAM_ATTEMPT" -> "Stun-Slam angesetzt"; case "STUN_SLAM_MISSED" -> "Stun-Slam-Fenster verpasst";
            case "SWORD_REENTRY" -> "Wieder anlaufen"; case "DRAIN" -> "Wasser aufnehmen";
            case "SWORD_BACK_OUT" -> "Kombo verlassen"; case "SWORD_HIT_SELECT" -> "Hit Select"; case "SWORD_JUMP_RESET" -> "Jump Reset";
            case "MELEE" -> "Nahkampf"; case "WHIFF" -> "Luftschlag"; case "MACE" -> "Mace"; case "BOW" -> "Bogen";
            case "CRYSTAL" -> "Kristall"; case "WIND" -> "Windladung"; case "HEAL" -> "Heilung";
            case "CROSSBOW" -> "Armbrust"; case "PEARL" -> "Enderperle"; case "ANCHOR" -> "Anker";
            case "POTION" -> "Wurftrank"; case "REPAIR" -> "Reparatur"; case "GLIDE" -> "Gleitflug";
            case "ROCKET" -> "Flugrakete"; case "WEB" -> "Spinnennetz"; case "WATER" -> "Wasser";
            case "LAVA" -> "Lava"; case "BLOCK" -> "Block setzen"; case "MINE" -> "Abbauen"; case "SHIELD" -> "Schild"; default -> "–";
        };
    }
}
