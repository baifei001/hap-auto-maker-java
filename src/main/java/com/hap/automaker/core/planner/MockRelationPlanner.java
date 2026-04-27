package com.hap.automaker.core.planner;

import com.hap.automaker.util.LoggerFactory;

import org.slf4j.Logger;

import java.util.*;

/**
 * Mock关联数据规划器
 *
 * Python 对应: mock_data_inline.py - apply_relation_phase() + planners/plan_mock_relations_gemini.py
 *
 * 职责:
 * - 根据工作表之间的关系规划关联数据
 * - 为从属端表的 Relation 字段分配目标表记录ID
 * - 按 tier 分批处理（明细端 tier=3 先处理，1:1从属端 tier=2 后处理）
 *
 * 关联规划策略:
 * - Round-robin 分配：从属端每条记录循环取目标表的 rowId
 * - Phase 2 不调用 AI，使用确定性的算法策略
 */
public class MockRelationPlanner implements Planner<MockRelationPlanner.Input, MockRelationPlanner.Output> {

    private static final Logger logger = LoggerFactory.getLogger(MockRelationPlanner.class);

    public MockRelationPlanner() {
    }

    @Override
    public String getName() {
        return "MockRelationPlanner";
    }

    @Override
    public Output plan(Input input) throws PlanningException {
        try {
            // 1. 构建 tierMap（确定每个工作表的层级）
            Map<String, Integer> tierMap = buildTierMap(input);

            // 2. 识别候选字段（需要填写关联的工作表和字段）
            List<RelationCandidate> candidates = buildCandidates(input);

            // 3. 规划关联分配
            List<RelationAssignmentPlan> plans = new ArrayList<>();

            // 按 tier 顺序处理：先 tier=3（明细端），再 tier=2（1:1从属端）
            for (int targetTier : List.of(3, 2)) {
                List<RelationCandidate> batch = candidates.stream()
                    .filter(c -> tierMap.getOrDefault(c.getWorksheetId(), 2) == targetTier)
                    .toList();

                for (RelationCandidate candidate : batch) {
                    RelationAssignmentPlan plan = planAssignments(candidate, input.getAllRowIds());
                    if (plan != null) {
                        plans.add(plan);
                    }
                }
            }

            logger.info("✓ 关联数据规划完成: {} 个关联字段", plans.size());

            return new Output(plans);

        } catch (Exception e) {
            throw new PlanningException(getName(), "Failed to plan relation data", e);
        }
    }

    /**
     * 构建 tierMap（确定每个工作表的层级）
     *
     * tier=1: 无主表或主表端（无出边subType=1或参与1-N但不是明细端）
     * tier=2: 1:1 从属端
     * tier=3: 1-N 明细端（有出边subType=1且都=1，且参与1-N pair）
     */
    private Map<String, Integer> buildTierMap(Input input) {
        Map<String, Integer> tierMap = new HashMap<>();

        for (String wsId : input.getAllRowIds().keySet()) {
            // 收集该表的出边 subType
            List<Integer> outgoingSubtypes = input.getRelationEdges().stream()
                .filter(e -> e.getSourceWorksheetId().equals(wsId))
                .map(RelationEdge::getSubType)
                .toList();

            // 检查是否参与 1-N pair
            boolean in1NPair = input.getRelationPairs().stream()
                .filter(p -> "1-N".equals(p.getPairType()))
                .anyMatch(p -> p.getWorksheetAId().equals(wsId) || p.getWorksheetBId().equals(wsId));

            // 明细端条件：有出边 subType=1 且都=1，且参与 1-N pair
            boolean isDetailEnd = !outgoingSubtypes.isEmpty()
                && outgoingSubtypes.stream().allMatch(s -> s == 1)
                && in1NPair;

            int tier;
            if (isDetailEnd) {
                tier = 3;
            } else if (!outgoingSubtypes.isEmpty()) {
                tier = 2; // 1:1 从属端
            } else {
                tier = 1; // 主表
            }

            tierMap.put(wsId, tier);
        }

        return tierMap;
    }

    /**
     * 识别候选字段（需要填写关联的工作表和字段）
     */
    private List<RelationCandidate> buildCandidates(Input input) {
        List<RelationCandidate> candidates = new ArrayList<>();

        Map<String, String> idToName = new HashMap<>();
        for (WorksheetInfo ws : input.getWorksheets()) {
            idToName.put(ws.getWorksheetId(), ws.getWorksheetName());
        }

        // 遍历所有工作表的关系边
        for (RelationEdge edge : input.getRelationEdges()) {
            String sourceWsId = edge.getSourceWorksheetId();
            String targetWsId = edge.getTargetWorksheetId();

            if (edge.getSubType() != 1) {
                continue; // 只处理 subType=1 的关联字段
            }

            // 获取源工作表的记录ID
            List<String> sourceRowIds = input.getAllRowIds().get(sourceWsId);
            if (sourceRowIds == null || sourceRowIds.isEmpty()) {
                continue;
            }

            // 获取目标工作表的记录ID
            List<String> targetRowIds = input.getAllRowIds().get(targetWsId);
            if (targetRowIds == null || targetRowIds.isEmpty()) {
                logger.warn("⚠ [{}] 目标表 {} 无 rowId，跳过关联字段 {}",
                    idToName.getOrDefault(sourceWsId, sourceWsId), targetWsId, edge.getFieldId());
                continue;
            }

            candidates.add(new RelationCandidate(
                sourceWsId,
                idToName.getOrDefault(sourceWsId, sourceWsId),
                edge.getFieldId(),
                edge.getFieldName(),
                targetWsId,
                targetRowIds,
                sourceRowIds
            ));
        }

        return candidates;
    }

    /**
     * 规划单个候选字段的关联分配（Round-robin 策略）
     */
    private RelationAssignmentPlan planAssignments(RelationCandidate candidate, Map<String, List<String>> allRowIds) {
        List<String> sourceRowIds = candidate.getSourceRowIds();
        List<String> targetRowIds = candidate.getTargetRowIds();

        if (targetRowIds.isEmpty()) {
            return null;
        }

        // Round-robin 分配
        List<RowAssignment> assignments = new ArrayList<>();
        for (int i = 0; i < sourceRowIds.size(); i++) {
            String sourceRowId = sourceRowIds.get(i);
            String targetRowId = targetRowIds.get(i % targetRowIds.size());
            assignments.add(new RowAssignment(sourceRowId, targetRowId));
        }

        return new RelationAssignmentPlan(
            candidate.getWorksheetId(),
            candidate.getWorksheetName(),
            candidate.getFieldId(),
            candidate.getFieldName(),
            candidate.getTargetWorksheetId(),
            assignments
        );
    }

    // ========== 输入类 ==========
    public static class Input {
        private final List<WorksheetInfo> worksheets;
        private final List<RelationPair> relationPairs;
        private final List<RelationEdge> relationEdges;
        private final Map<String, List<String>> allRowIds;

        public Input(List<WorksheetInfo> worksheets,
                     List<RelationPair> relationPairs,
                     List<RelationEdge> relationEdges,
                     Map<String, List<String>> allRowIds) {
            this.worksheets = worksheets != null ? worksheets : List.of();
            this.relationPairs = relationPairs != null ? relationPairs : List.of();
            this.relationEdges = relationEdges != null ? relationEdges : List.of();
            this.allRowIds = allRowIds != null ? allRowIds : Map.of();
        }

        public List<WorksheetInfo> getWorksheets() { return worksheets; }
        public List<RelationPair> getRelationPairs() { return relationPairs; }
        public List<RelationEdge> getRelationEdges() { return relationEdges; }
        public Map<String, List<String>> getAllRowIds() { return allRowIds; }
    }

    public static class WorksheetInfo {
        private final String worksheetId;
        private final String worksheetName;
        private final List<FieldInfo> fields;

        public WorksheetInfo(String worksheetId, String worksheetName, List<FieldInfo> fields) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fields = fields != null ? fields : List.of();
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public List<FieldInfo> getFields() { return fields; }
    }

    public static class FieldInfo {
        private final String fieldId;
        private final String name;
        private final String type;

        public FieldInfo(String fieldId, String name, String type) {
            this.fieldId = fieldId;
            this.name = name;
            this.type = type;
        }

        public String getFieldId() { return fieldId; }
        public String getName() { return name; }
        public String getType() { return type; }
    }

    public static class RelationPair {
        private final String worksheetAId;
        private final String worksheetBId;
        private final String pairType;

        public RelationPair(String worksheetAId, String worksheetBId, String pairType) {
            this.worksheetAId = worksheetAId;
            this.worksheetBId = worksheetBId;
            this.pairType = pairType != null ? pairType : "ambiguous";
        }

        public String getWorksheetAId() { return worksheetAId; }
        public String getWorksheetBId() { return worksheetBId; }
        public String getPairType() { return pairType; }
    }

    public static class RelationEdge {
        private final String sourceWorksheetId;
        private final String targetWorksheetId;
        private final String fieldId;
        private final String fieldName;
        private final int subType;
        private final boolean bidirectional;

        public RelationEdge(String sourceWorksheetId, String targetWorksheetId,
                           String fieldId, String fieldName,
                           int subType, boolean bidirectional) {
            this.sourceWorksheetId = sourceWorksheetId;
            this.targetWorksheetId = targetWorksheetId;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.subType = subType;
            this.bidirectional = bidirectional;
        }

        public String getSourceWorksheetId() { return sourceWorksheetId; }
        public String getTargetWorksheetId() { return targetWorksheetId; }
        public String getFieldId() { return fieldId; }
        public String getFieldName() { return fieldName; }
        public int getSubType() { return subType; }
        public boolean isBidirectional() { return bidirectional; }
    }

    // ========== 内部类 ==========
    private static class RelationCandidate {
        private final String worksheetId;
        private final String worksheetName;
        private final String fieldId;
        private final String fieldName;
        private final String targetWorksheetId;
        private final List<String> targetRowIds;
        private final List<String> sourceRowIds;

        RelationCandidate(String worksheetId, String worksheetName,
                         String fieldId, String fieldName,
                         String targetWorksheetId, List<String> targetRowIds,
                         List<String> sourceRowIds) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.targetWorksheetId = targetWorksheetId;
            this.targetRowIds = targetRowIds;
            this.sourceRowIds = sourceRowIds;
        }

        String getWorksheetId() { return worksheetId; }
        String getWorksheetName() { return worksheetName; }
        String getFieldId() { return fieldId; }
        String getFieldName() { return fieldName; }
        String getTargetWorksheetId() { return targetWorksheetId; }
        List<String> getTargetRowIds() { return targetRowIds; }
        List<String> getSourceRowIds() { return sourceRowIds; }
    }

    // ========== 输出类 ==========
    public static class Output {
        private final List<RelationAssignmentPlan> plans;

        public Output(List<RelationAssignmentPlan> plans) {
            this.plans = plans != null ? plans : List.of();
        }

        public List<RelationAssignmentPlan> getPlans() { return plans; }
    }

    public static class RelationAssignmentPlan {
        private final String worksheetId;
        private final String worksheetName;
        private final String fieldId;
        private final String fieldName;
        private final String targetWorksheetId;
        private final List<RowAssignment> assignments;

        public RelationAssignmentPlan(String worksheetId, String worksheetName,
                                      String fieldId, String fieldName,
                                      String targetWorksheetId,
                                      List<RowAssignment> assignments) {
            this.worksheetId = worksheetId;
            this.worksheetName = worksheetName;
            this.fieldId = fieldId;
            this.fieldName = fieldName;
            this.targetWorksheetId = targetWorksheetId;
            this.assignments = assignments != null ? assignments : List.of();
        }

        public String getWorksheetId() { return worksheetId; }
        public String getWorksheetName() { return worksheetName; }
        public String getFieldId() { return fieldId; }
        public String getFieldName() { return fieldName; }
        public String getTargetWorksheetId() { return targetWorksheetId; }
        public List<RowAssignment> getAssignments() { return assignments; }
    }

    public static class RowAssignment {
        private final String sourceRowId;
        private final String targetRowId;

        public RowAssignment(String sourceRowId, String targetRowId) {
            this.sourceRowId = sourceRowId;
            this.targetRowId = targetRowId;
        }

        public String getSourceRowId() { return sourceRowId; }
        public String getTargetRowId() { return targetRowId; }
    }
}
