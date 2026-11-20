package com.learnloop.generation;

import com.learnloop.learning.BktClient;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import static com.learnloop.generation.AdaptiveMapper.*;
import static com.learnloop.generation.GenerationProtocol.Source;

/** 模型只建议归组。实际身份、来源范围与旧题关联都由 Java 验证后写入。 */
@Service
class AdaptiveCatalog {
    static final String CATALOG_VERSION = "catalog-stable-v2";
    private final JsonMapper json = JsonMapper.builder().build();
    record Legacy(String id, long blockId, String name, String definition) {}
    record CatalogTopic(String id,String chapter,String groupName,String name,String definition,List<String> aliases,String granularity) {}
    record CatalogRequest(List<Source> sources, List<CatalogTopic> topics, List<Legacy> legacy) {}
    record Entry(String existingId, long blockId, String quote, String chapter, String groupName,
                 String name, String definition, List<String> legacyIds, List<String> aliases, String granularity) {
        Entry(String existingId,long blockId,String quote,String chapter,String groupName,String name,String definition,List<String> legacyIds) {
            this(existingId,blockId,quote,chapter,groupName,name,definition,legacyIds,List.of(),"MEDIUM");
        }
        Entry { aliases=aliases==null?List.of():aliases; granularity=granularity==null?"MEDIUM":granularity; }
    }
    record BlockResult(long blockId,String status,String reason) {}
    record CatalogResult(List<Entry> entries,List<BlockResult> blocks) {
        CatalogResult(List<Entry> entries) { this(entries,List.of()); }
        CatalogResult { blocks=blocks==null?List.of():blocks; }
    }
    record LeafView(String id, String name, boolean hasRecentError) {}
    record GroupView(String chapter, String name, Double probability, int evidenceCount, List<LeafView> topics) {}
    record Data(List<Topic> topics, List<Observation> observations, Map<String, AdaptivePolicy.GroupState> mastery) {}

    private final AdaptiveMapper mapper;
    AdaptiveCatalog(AdaptiveMapper mapper) { this.mapper = mapper; }

    /**
     * 每个成功批次都为实际发布的新考点补卡，复习卡不再限制出题范围。
     * 调用方已持有用户锁；SQL 只插入缺失卡，因此重试和变式不会重置既有 FSRS 排期。
     */
    void ensurePublishedReviewCards(long user, String job) {
        mapper.insertMissingReviewCards(user, job, GenerationService.now());
    }

    void restoreReviewCards(long user) {
        // 按历史批次逐个补缺，不要求整份资料一张卡都没有，兼容旧版只为首批建卡的数据。
        for (String job : mapper.jobsMissingCards(user)) ensurePublishedReviewCards(user, job);
    }

    CatalogRequest request(long user, long material, long attempt, List<Source> sources,
                           List<GenerationProtocol.KnownConcept> concepts) {
        return request(user,material,attempt,sources,concepts,false);
    }

    CatalogRequest request(long user,long material,long attempt,List<Source> sources,
                           List<GenerationProtocol.KnownConcept> concepts,boolean refresh) {
        // 旧版 indexed 标记不能区分空结果；只有本版 READY/明确非知识页才可缓存。
        Set<Long> indexed = new HashSet<>();
        if (!refresh) for (var state:mapper.catalogStates(user,material,attempt,CATALOG_VERSION)) {
            if (!"RETRYABLE_EMPTY".equals(state.status())) indexed.add(state.blockId());
        }
        List<Source> missing = sources.stream().filter(source -> !indexed.contains(source.blockId())).toList();
        if (missing.isEmpty()) return null;
        Set<Long> blocks = new HashSet<>(missing.stream().map(Source::blockId).toList());
        List<Legacy> legacy = concepts.stream().filter(c -> blocks.contains(c.blockId()))
                .map(c -> new Legacy(c.conceptId(), c.blockId(), c.name(), cap(c.definition(), 140))).toList();
        Map<String,Profile> profiles=profiles(user,material);
        List<CatalogTopic> topics = mapper.topics(user, material).stream().map(t -> {
            Profile profile=profiles.get(t.id());
            return new CatalogTopic(t.id(),t.chapter(),t.groupName(),t.name(),cap(t.definition(),300),
                    aliases(profile),profile==null?"MEDIUM":profile.granularity());
        }).toList();
        return new CatalogRequest(missing, topics, legacy);
    }

    void save(long user, long material, long attempt, CatalogRequest request, CatalogResult result) {
        if (request == null) return;
        if (result == null || result.entries() == null || result.entries().size() > 60) throw GenerationService.invalidResult();
        Map<Long, String> texts = new HashMap<>();
        request.sources().forEach(s -> texts.put(s.blockId(), s.text()));
        Map<String, Legacy> legacy = new HashMap<>();
        request.legacy().forEach(item -> legacy.put(item.id(), item));
        Map<String, Topic> byId = new HashMap<>(), byKey = new HashMap<>();
        Map<String,Profile> profiles=profiles(user,material);
        Map<Long,BlockResult> outcomes=new HashMap<>();
        if(result.blocks().size()>request.sources().size()) throw GenerationService.invalidResult();
        for(var outcome:result.blocks()) {
            if(!texts.containsKey(outcome.blockId()) || outcomes.put(outcome.blockId(),outcome)!=null
                    || !Set.of("READY","NO_KNOWLEDGE","RETRYABLE_EMPTY").contains(outcome.status())
                    || !valid(outcome.reason(),300)) throw GenerationService.invalidResult();
        }
        Set<Long> covered=new HashSet<>();
        for (Topic topic : mapper.topics(user, material)) {
            byId.put(topic.id(), topic);
            byKey.put(AdaptivePolicy.topicKey(topic), topic);
        }
        for (Entry entry : result.entries()) {
            String source = texts.get(entry.blockId());
            if (source == null || !valid(entry.quote(), 300) || entry.quote().codePointCount(0, entry.quote().length()) < 8
                    || !source.contains(entry.quote()) || entry.legacyIds() == null || entry.legacyIds().size() > 20) {
                throw GenerationService.invalidResult();
            }
            if (!valid(entry.chapter(), 120) || !valid(entry.groupName(), 120) || !valid(entry.name(), 120)
                    || !valid(entry.definition(), 300) || entry.aliases().size()>12
                    || entry.aliases().stream().anyMatch(alias->!valid(alias,120))
                    || !Set.of("COARSE","MEDIUM","FINE").contains(entry.granularity())) throw GenerationService.invalidResult();
            Topic topic = byId.get(entry.existingId());
            if (entry.existingId() != null && topic == null) throw GenerationService.invalidResult();
            for (String oldId : entry.legacyIds()) {
                Legacy old = legacy.get(oldId);
                if (old == null || old.blockId() != entry.blockId()) throw GenerationService.invalidResult();
                String linked = mapper.linkedTopic(user, oldId);
                if (linked != null) {
                    if (topic != null && !topic.id().equals(linked)) throw GenerationService.invalidResult();
                    topic = byId.get(linked);
                }
            }
            if (topic == null) {
                Topic proposed = new Topic(GenerationService.uuid(), entry.chapter().strip(), entry.groupName().strip(),
                        entry.name().strip(), entry.definition().strip());
                String key = AdaptivePolicy.topicKey(proposed);
                // 同名不代表同义：自动归并还要求同知识组且定义一致；语义复用由显式 existingId 表达。
                topic = byId.values().stream().filter(t -> AdaptivePolicy.groupKey(t).equals(AdaptivePolicy.groupKey(proposed))
                        && AdaptivePolicy.normalize(t.definition()).equals(AdaptivePolicy.normalize(proposed.definition())))
                        .findFirst().orElse(null);
                if (topic == null) {
                    topic = proposed;
                    // 旧学习概念按正文块+主名称绑定。同名异义新增语义限定，避免两个 ID 误绑同一概念。
                    Set<String> usedNames=new HashSet<>();
                    byId.values().forEach(t->usedNames.add(AdaptivePolicy.normalize(t.name())));
                    if(usedNames.contains(AdaptivePolicy.normalize(topic.name()))) {
                        String base=cap(topic.name(),60)+"（"+cap(topic.definition(),50)+"）";
                        String label=base;
                        for(int suffix=2;usedNames.contains(AdaptivePolicy.normalize(label));suffix++) label=base+"-"+suffix;
                        topic=new Topic(topic.id(),topic.chapter(),topic.groupName(),label,topic.definition());
                    }
                    String uniqueKey=byKey.containsKey(key)?key+"\u001f"+AdaptivePolicy.normalize(topic.definition()):key;
                    mapper.insertTopic(user, material, GenerationService.hash(uniqueKey), topic);
                    byId.put(topic.id(), topic);
                    byKey.put(key, topic);
                }
            }
            // 不改已有章节/知识组和主名称，避免把历史 BKT 证据迁移到另一个能力组。
            Profile previous=profiles.get(topic.id());
            TreeSet<String> names=new TreeSet<>(aliases(previous));
            names.addAll(entry.aliases());
            if(!AdaptivePolicy.normalize(topic.name()).equals(AdaptivePolicy.normalize(entry.name()))) names.add(entry.name().strip());
            String canonicalName=AdaptivePolicy.normalize(topic.name());
            names.removeIf(name->AdaptivePolicy.normalize(name).equals(canonicalName));
            List<String> savedAliases=names.stream().limit(12).toList();
            String level=previous==null?entry.granularity():rank(previous.granularity())<=rank(entry.granularity())?previous.granularity():entry.granularity();
            mapper.saveProfile(user,topic.id(),json.writeValueAsString(savedAliases),level);
            profiles.put(topic.id(),new Profile(topic.id(),json.writeValueAsString(savedAliases),level));
            mapper.addSource(user, material, attempt, entry.blockId(), topic.id(), entry.quote());
            covered.add(entry.blockId());
            for (String oldId : entry.legacyIds()) bind(user, oldId, topic.id());
        }
        for (Source source : request.sources()) {
            BlockResult outcome=outcomes.get(source.blockId());
            boolean hasTopics=covered.contains(source.blockId());
            if(hasTopics && outcome!=null && "NO_KNOWLEDGE".equals(outcome.status())) throw GenerationService.invalidResult();
            String status=hasTopics?"READY":outcome!=null && "NO_KNOWLEDGE".equals(outcome.status())?"NO_KNOWLEDGE":"RETRYABLE_EMPTY";
            String reason=hasTopics?"已提取可靠考点":outcome==null?"模型未给出考点，用户下次出题时将重新提取":outcome.reason();
            mapper.saveCatalogState(user,material,attempt,source.blockId(),CATALOG_VERSION,status,reason,GenerationService.now());
            if(!"RETRYABLE_EMPTY".equals(status)) mapper.markIndexed(user,material,attempt,source.blockId());
        }
    }

    List<AdaptivePolicy.Candidate> candidates(long user, long material, long attempt, Set<Long> blocks, List<Topic> topics) {
        return candidates(user,material,attempt,blocks,topics,"FINE");
    }

    List<AdaptivePolicy.Candidate> candidates(long user,long material,long attempt,Set<Long> blocks,List<Topic> topics,String granularity) {
        Map<String,Profile> profiles=profiles(user,material);
        Map<String, Long> source = new HashMap<>();
        for (AdaptiveMapper.Source row : mapper.sources(user, material, attempt)) {
            if (blocks.contains(row.blockId())) source.putIfAbsent(row.topicId(), row.blockId());
        }
        Map<String, Previous> previous = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (Previous item : mapper.previous(user, material)) {
            previous.putIfAbsent(item.topicId(), item);
            counts.merge(item.topicId(), 1, Integer::sum);
        }
        return topics.stream().filter(topic -> source.containsKey(topic.id()))
                .filter(topic -> rank(profiles.containsKey(topic.id())?profiles.get(topic.id()).granularity():"MEDIUM")<=rank(granularity))
                .map(topic -> new AdaptivePolicy.Candidate(topic, source.get(topic.id()), previous.get(topic.id()),
                        counts.getOrDefault(topic.id(), 0)))
                .toList();
    }

    String noise(long user,long material,long attempt,long block) { return mapper.noise(user,material,attempt,block); }

    String emptyNotice(long user,long material,long attempt,Set<Long> blocks) {
        var states=mapper.catalogStates(user,material,attempt,CATALOG_VERSION).stream()
                .filter(state->blocks.contains(state.blockId()) && !"READY".equals(state.status())).toList();
        if(states.isEmpty()) return null;
        String reasons=String.join("；",states.stream().map(CatalogState::reason).distinct().limit(2).toList());
        return cap("选区存在非知识内容或待重新提取的正文："+reasons,600);
    }

    private Map<String,Profile> profiles(long user,long material) {
        Map<String,Profile> result=new HashMap<>();
        mapper.profiles(user,material).forEach(profile->result.put(profile.topicId(),profile));
        return result;
    }
    private List<String> aliases(Profile profile) { return profile==null?List.of():List.of(json.readValue(profile.aliasesJson(),String[].class)); }
    private static int rank(String level) { return switch(level) {case "COARSE"->0;case "FINE"->2;default->1;}; }

    void linkExactLegacy(long user, long material, long attempt, List<GenerationProtocol.KnownConcept> concepts) {
        Map<String, List<Topic>> byName = new HashMap<>();
        Map<String, Topic> byId = new HashMap<>();
        for (Topic topic : mapper.topics(user, material)) byId.put(topic.id(), topic);
        for (AdaptiveMapper.Source source : mapper.sources(user, material, attempt)) {
            Topic topic = byId.get(source.topicId());
            byName.computeIfAbsent(source.blockId() + ":" + AdaptivePolicy.normalize(topic.name()),
                    ignored -> new ArrayList<>()).add(topic);
        }
        for (var concept : concepts) {
            if (mapper.linkedTopic(user, concept.conceptId()) != null) continue;
            List<Topic> matches = byName.get(concept.blockId() + ":" + AdaptivePolicy.normalize(concept.name()));
            if (matches != null && matches.size() == 1
                    && AdaptivePolicy.normalize(concept.definition()).equals(AdaptivePolicy.normalize(matches.getFirst().definition()))) {
                bind(user, concept.conceptId(), matches.getFirst().id());
            }
        }
    }

    Data data(long user, long material) {
        List<Topic> topics = mapper.topics(user, material);
        List<Observation> observations = mapper.observations(user, material, BktClient.MODEL_VERSION);
        return new Data(topics, observations, AdaptivePolicy.mastery(topics, observations));
    }

    List<GroupView> groups(long user, long material) {
        Data data = data(user, material);
        Map<String, Observation> latest = new HashMap<>();
        data.observations().forEach(event -> latest.put(event.topicId(), event));
        Map<String, List<Topic>> groups = new LinkedHashMap<>();
        data.topics().stream().sorted(Comparator.comparing(Topic::chapter).thenComparing(Topic::groupName)
                .thenComparing(Topic::name)).forEach(topic -> groups.computeIfAbsent(AdaptivePolicy.groupKey(topic),
                ignored -> new ArrayList<>()).add(topic));
        return groups.entrySet().stream().map(group -> {
            Topic first = group.getValue().getFirst();
            AdaptivePolicy.GroupState state = data.mastery().get(group.getKey());
            return new GroupView(first.chapter(), first.groupName(), state == null ? null : state.probability(),
                    state == null ? 0 : state.evidenceCount(), group.getValue().stream().map(topic -> {
                        Observation last = latest.get(topic.id());
                        return new LeafView(topic.id(), topic.name(), last != null && !last.correct()
                                && !last.createdAt().isBefore(GenerationService.now().minusDays(30)));
                    }).toList());
        }).toList();
    }

    void bind(long user, String concept, String topic) {
        String existing = mapper.linkedTopic(user, concept);
        if (existing == null) mapper.link(user, concept, topic);
        else if (!existing.equals(topic)) throw GenerationService.invalidResult();
    }

    List<Previous> history(long user, long material) { return mapper.previous(user, material); }

    void recordChoice(long user, String question, AdaptivePolicy.Target target) {
        mapper.recordChoice(user, question, target.candidate().topic().id(), target.reason(), AdaptivePolicy.VERSION);
    }

    private static boolean valid(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t');
    }

    private static String cap(String text, int max) {
        if (text == null) return "";
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
    }
}
