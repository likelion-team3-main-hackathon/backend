package tri_lion.health.service.chat;

import static tri_lion.health.dto.chat.ChatDtos.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.chat.PendingAiAction;
import tri_lion.health.domain.health.AiJob;
import tri_lion.health.domain.record.*;
import tri_lion.health.domain.routine.*;
import tri_lion.health.dto.request.record.RecordRequest;
import tri_lion.health.dto.request.routine.RoutineRequests;
import tri_lion.health.exception.ApiException;
import tri_lion.health.repository.chat.ChatRepositories;
import tri_lion.health.repository.routine.RoutineRepositories;
import tri_lion.health.security.AuthenticatedUser;
import tri_lion.health.service.expert.ExpansionService;
import tri_lion.health.service.record.RecordService;
import tri_lion.health.service.routine.RoutineService;

@Service
public class ChatActionService {
    private static final String ACTION_PLAN = "CHAT_ACTION_PLAN";
    private static final int MAX_OPERATIONS = 50;
    private static final Set<String> PATCH_EXERCISE_KEYS =
            Set.of(
                    "routineId",
                    "exerciseId",
                    "name",
                    "targetValue",
                    "targetUnit",
                    "sets",
                    "restSeconds",
                    "memo",
                    "excludeFromAiAdjustment");
    private static final Set<String> PATCH_ROUTINE_KEYS =
            Set.of("routineId", "title", "description", "endDate", "aiAdjustmentAllowed", "status");
    private static final Set<String> PATCH_ROUTINE_ITEM_KEYS =
            Set.of(
                    "routineId",
                    "routineItemId",
                    "title",
                    "content",
                    "targetValue",
                    "targetUnit",
                    "sets",
                    "restSeconds",
                    "memo",
                    "excludeFromAiAdjustment");
    private static final Set<String> ADJUST_KEYS = Set.of("routineId", "reason", "userMessage");
    private static final Set<String> RECORD_KEYS =
            Set.of("routineItemId", "type", "details", "condition");
    private static final Set<String> CREATE_ROUTINE_KEYS =
            Set.of("analysisId", "title", "goal", "startDate", "durationWeeks", "items");
    private static final Set<String> PERSONALIZE_CURRICULUM_KEYS =
            Set.of(
                    "curriculumId",
                    "analysisId",
                    "startDate",
                    "durationWeeks",
                    "excludedItemIds",
                    "replacementItems");
    private static final Set<String> CART_KEYS = Set.of("routineId", "partner", "items");
    private static final Set<String> GENERATED_ITEM_KEYS =
            Set.of(
                    "dayOffset",
                    "sectionType",
                    "sectionTitle",
                    "itemType",
                    "title",
                    "content",
                    "scheduledTime",
                    "targetValue",
                    "targetUnit",
                    "sets",
                    "restSeconds",
                    "memo");
    private static final Set<String> PERSONALIZED_ITEM_KEYS =
            Set.of(
                    "sourceItemId",
                    "activityType",
                    "title",
                    "description",
                    "durationMinutes",
                    "details");
    private static final Set<String> CART_ITEM_KEYS = Set.of("marketItemId", "quantity");
    private static final Set<String> CONDITION_KEYS = Set.of("energyLevel", "painLevel", "memo");

    private final ChatRepositories.PendingActions pendingActions;
    private final RoutineRepositories.Items routineItems;
    private final RoutineService routineService;
    private final RecordService recordService;
    private final ExpansionService expansionService;
    private final ChatbotExpansionService chatbotExpansion;
    private final ChatHistoryService history;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;

    public ChatActionService(
            ChatRepositories.PendingActions pendingActions,
            RoutineRepositories.Items routineItems,
            RoutineService routineService,
            RecordService recordService,
            ExpansionService expansionService,
            ChatbotExpansionService chatbotExpansion,
            ChatHistoryService history,
            AuthenticatedUser auth,
            ObjectMapper json) {
        this.pendingActions = pendingActions;
        this.routineItems = routineItems;
        this.routineService = routineService;
        this.recordService = recordService;
        this.expansionService = expansionService;
        this.chatbotExpansion = chatbotExpansion;
        this.history = history;
        this.auth = auth;
        this.json = json;
    }

    @Transactional
    public PendingAiAction prepare(AiDecision decision) {
        Long userId = auth.sensitive().getId();
        validateOperations(decision.operations());
        String message =
                decision.confirmationMessage() == null || decision.confirmationMessage().isBlank()
                        ? decision.answer()
                        : decision.confirmationMessage();
        if (message == null || message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI가 확인 문장을 만들지 못했습니다.");
        }
        try {
            return pendingActions.save(
                    new PendingAiAction(
                            userId,
                            ACTION_PLAN,
                            json.writeValueAsString(Map.of("operations", decision.operations())),
                            message));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 변경안을 저장하지 못했습니다.");
        }
    }

    @Transactional
    public ActionResultResponse confirm(Long actionId) {
        Long userId = auth.sensitive().getId();
        PendingAiAction action = ownedForUpdate(actionId, userId);
        checkPending(action);
        List<AiOperation> operations = readOperations(action);
        validateOperations(operations);
        List<String> results = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            results.add(execute(action, operations.get(index), index));
        }
        String result = String.join("\n", results);
        action.execute();
        pendingActions.saveAndFlush(action);
        history.saveActionResult(userId, action.getId(), result, "ACTION_EXECUTED");
        return new ActionResultResponse(action.getId(), action.getStatus().name(), result, null);
    }

    @Transactional
    public ActionResultResponse cancel(Long actionId) {
        Long userId = auth.sensitive().getId();
        PendingAiAction action = ownedForUpdate(actionId, userId);
        checkPending(action);
        action.cancel();
        pendingActions.saveAndFlush(action);
        history.saveActionResult(userId, action.getId(), "변경을 취소했습니다.", "ACTION_CANCELLED");
        return new ActionResultResponse(
                action.getId(), action.getStatus().name(), "변경을 취소했습니다.", null);
    }

    private PendingAiAction ownedForUpdate(Long actionId, Long userId) {
        return pendingActions
                .findOwnedForUpdate(actionId, userId)
                .orElseThrow(() -> ApiException.notFound("변경안을 찾을 수 없습니다."));
    }

    private void checkPending(PendingAiAction action) {
        if (action.getStatus() != PendingAiAction.Status.PENDING) {
            throw ApiException.conflict("이미 처리된 변경안입니다.");
        }
        if (action.isExpired()) {
            action.expire();
            throw ApiException.conflict("변경안의 확인 가능 시간이 지났습니다. 다시 요청해 주세요.");
        }
    }

    private void validate(String methodName, Map<String, Object> arguments) {
        if (arguments == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AI 변경 인자가 없습니다.");
        }
        switch (methodName) {
            case "routineService.patchRoutineItem" -> validatePatchRoutineItem(arguments);
            case "routineService.patchExercise" -> validatePatchExercise(arguments);
            case "routineService.patch" -> validatePatchRoutine(arguments);
            case "routineService.adjust" -> validateAdjust(arguments);
            case "recordService.create" -> validateRecord(arguments);
            case "routineService.createGeneratedRoutine" -> validateCreateRoutine(arguments);
            case "routineService.personalizeCurriculum" -> validatePersonalizeCurriculum(arguments);
            case "expansionService.createMealCart" -> validateMealCart(arguments);
            case "chatbotExpansion.patchWellnessProfile",
                    "chatbotExpansion.batchPatchRoutineItems",
                    "chatbotExpansion.rescheduleRoutineItems",
                    "chatbotExpansion.shiftRoutineItemTimes",
                    "chatbotExpansion.deleteRoutineItems",
                    "chatbotExpansion.updateActivityRecord",
                    "chatbotExpansion.deleteActivityRecord",
                    "chatbotExpansion.deleteHealthRecord",
                    "chatbotExpansion.pauseRoutine",
                    "chatbotExpansion.resumeRoutine",
                    "chatbotExpansion.keepOnlyRoutineActive",
                    "chatbotExpansion.updateNotificationSettings",
                    "chatbotExpansion.registerChatImageAndAnalyze" ->
                    chatbotExpansion.validate(methodName, arguments);
            default ->
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "AI가 지원하지 않는 작업을 제안했습니다.");
        }
    }

    private void validateOperations(List<AiOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "실행할 AI 작업이 없습니다.");
        }
        if (operations.size() > MAX_OPERATIONS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "한 번에 변경할 수 있는 작업은 최대 50개입니다.");
        }
        for (AiOperation operation : operations) {
            if (operation == null
                    || operation.methodName() == null
                    || operation.methodName().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "AI 작업의 메서드명이 없습니다.");
            }
            validate(operation.methodName(), operation.arguments());
        }
    }

    private void validatePatchRoutineItem(Map<String, Object> arguments) {
        allowOnly(arguments, PATCH_ROUTINE_ITEM_KEYS);
        Long routineId = requiredLong(arguments, "routineId");
        Long routineItemId = requiredLong(arguments, "routineItemId");
        Routine routine = routineService.owned(routineId);
        routineService.requireContentMutable(routine);
        ExerciseItem item =
                routineItems
                        .findByIdAndRoutineIdAndDeletedAtIsNull(routineItemId, routineId)
                        .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
        routineService.requirePendingItem(item);
        requireAnyChange(arguments, Set.of("routineId", "routineItemId"));
        nonBlankText(arguments, "title", 200);
        maxText(arguments, "content", 5000);
        maxText(arguments, "memo", 500);
        decimalRange(arguments, "targetValue", new BigDecimal("0.1"), new BigDecimal("100000"));
        integerRange(arguments, "sets", 1, 100);
        integerRange(arguments, "restSeconds", 0, 3600);
        String unit = optionalText(arguments, "targetUnit");
        if (unit != null) enumValue(ExerciseItem.Unit.class, unit, "목표 단위");
    }

    private void validatePatchExercise(Map<String, Object> arguments) {
        allowOnly(arguments, PATCH_EXERCISE_KEYS);
        Long routineId = requiredLong(arguments, "routineId");
        Long exerciseId = requiredLong(arguments, "exerciseId");
        Routine routine = routineService.owned(routineId);
        routineService.requireContentMutable(routine);
        ExerciseItem item =
                routineItems
                        .findByIdAndRoutineIdAndDeletedAtIsNull(exerciseId, routineId)
                        .orElseThrow(() -> ApiException.notFound("운동 항목을 찾을 수 없습니다."));
        routineService.requirePendingItem(item);
        requireAnyChange(arguments, Set.of("routineId", "exerciseId"));
        decimalRange(arguments, "targetValue", new BigDecimal("0.1"), new BigDecimal("100000"));
        integerRange(arguments, "sets", 1, 100);
        integerRange(arguments, "restSeconds", 0, 3600);
        nonBlankText(arguments, "name", 200);
        maxText(arguments, "memo", 500);
        String unit = optionalText(arguments, "targetUnit");
        if (unit != null) enumValue(ExerciseItem.Unit.class, unit, "목표 단위");
    }

    private void validatePatchRoutine(Map<String, Object> arguments) {
        allowOnly(arguments, PATCH_ROUTINE_KEYS);
        Long routineId = requiredLong(arguments, "routineId");
        Routine routine = routineService.owned(routineId);
        requireAnyChange(arguments, Set.of("routineId"));
        nonBlankText(arguments, "title", 200);
        maxText(arguments, "description", 2000);
        String status = optionalText(arguments, "status");
        if (status != null) enumValue(Routine.Status.class, status, "루틴 상태");
        routineService.validatePatchState(routine, status);
        LocalDate endDate = optionalDate(arguments, "endDate");
        if (endDate != null && endDate.isBefore(routine.getStartDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "종료일은 시작일보다 빠를 수 없습니다.");
        }
    }

    private void validateAdjust(Map<String, Object> arguments) {
        allowOnly(arguments, ADJUST_KEYS);
        Routine routine = routineService.owned(requiredLong(arguments, "routineId"));
        routineService.requireActive(routine);
        if (!routine.isAiAdjustmentAllowed()) {
            throw ApiException.conflict("AI 재조정이 허용되지 않은 루틴입니다.");
        }
        requiredText(arguments, "reason", 500);
        maxText(arguments, "userMessage", 2000);
    }

    private void validateRecord(Map<String, Object> arguments) {
        allowOnly(arguments, RECORD_KEYS);
        ActivityType type =
                enumValue(ActivityType.class, requiredText(arguments, "type", 30), "활동 유형");
        Long routineItemId = optionalLong(arguments, "routineItemId");
        if (routineItemId != null) {
            ExerciseItem item =
                    routineItems
                            .findById(routineItemId)
                            .filter(value -> value.getDeletedAt() == null)
                            .orElseThrow(() -> ApiException.notFound("루틴 항목을 찾을 수 없습니다."));
            Routine routine = routineService.owned(item.getRoutineId());
            routineService.requireActive(routine);
            routineService.requireRecordableItem(item);
            if (!type.name().equals(item.getItemType())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "루틴 항목과 기록 유형이 일치하지 않습니다.");
            }
        }
        Map<String, Object> condition = optionalMap(arguments, "condition");
        if (condition != null) {
            allowOnly(condition, CONDITION_KEYS);
            integerRange(condition, "energyLevel", 1, 5);
            integerRange(condition, "painLevel", 0, 5);
            maxText(condition, "memo", 500);
        }
        Map<String, Object> details = optionalMap(arguments, "details");
        if (routineItemId == null
                && (details == null || details.isEmpty())
                && (condition == null || condition.values().stream().allMatch(Objects::isNull))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "저장할 수행 내용이 필요합니다.");
        }
        if (type == ActivityType.WEIGHT) {
            if (details == null || !details.containsKey("weightKg")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "체중 기록에는 weightKg 값이 필요합니다.");
            }
            decimalRange(details, "weightKg", new BigDecimal("20"), new BigDecimal("500"));
        }
    }

    private void validateCreateRoutine(Map<String, Object> arguments) {
        allowOnly(arguments, CREATE_ROUTINE_KEYS);
        routineService.validateChatGeneration(createRoutineRequest(arguments));
    }

    private void validatePersonalizeCurriculum(Map<String, Object> arguments) {
        allowOnly(arguments, PERSONALIZE_CURRICULUM_KEYS);
        routineService.validateCurriculumPersonalization(
                curriculumPersonalizationRequest(arguments));
    }

    private void validateMealCart(Map<String, Object> arguments) {
        allowOnly(arguments, CART_KEYS);
        List<Map<String, Object>> items = requiredMapList(arguments, "items", 50);
        items.forEach(item -> allowOnly(item, CART_ITEM_KEYS));
        expansionService.validateCart(
                requiredLong(arguments, "routineId"),
                requiredText(arguments, "partner", 100),
                items);
    }

    private String execute(PendingAiAction action, AiOperation operation, int operationIndex) {
        Map<String, Object> arguments = operation.arguments();
        return switch (operation.methodName()) {
            case "routineService.patchRoutineItem" -> executePatchRoutineItem(arguments);
            case "routineService.patchExercise" -> executePatchExercise(arguments);
            case "routineService.patch" -> executePatchRoutine(arguments);
            case "routineService.adjust" -> executeAdjust(action, arguments, operationIndex);
            case "recordService.create" -> executeRecord(arguments);
            case "routineService.createGeneratedRoutine" -> executeCreateRoutine(arguments);
            case "routineService.personalizeCurriculum" -> executePersonalizeCurriculum(arguments);
            case "expansionService.createMealCart" -> executeMealCart(arguments);
            case "chatbotExpansion.patchWellnessProfile",
                    "chatbotExpansion.batchPatchRoutineItems",
                    "chatbotExpansion.rescheduleRoutineItems",
                    "chatbotExpansion.shiftRoutineItemTimes",
                    "chatbotExpansion.deleteRoutineItems",
                    "chatbotExpansion.updateActivityRecord",
                    "chatbotExpansion.deleteActivityRecord",
                    "chatbotExpansion.deleteHealthRecord",
                    "chatbotExpansion.pauseRoutine",
                    "chatbotExpansion.resumeRoutine",
                    "chatbotExpansion.keepOnlyRoutineActive",
                    "chatbotExpansion.updateNotificationSettings",
                    "chatbotExpansion.registerChatImageAndAnalyze" ->
                    chatbotExpansion.execute(operation.methodName(), arguments);
            default ->
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY, "AI가 지원하지 않는 작업을 제안했습니다.");
        };
    }

    private String executePatchRoutineItem(Map<String, Object> arguments) {
        ExerciseItem item =
                routineService.patchRoutineItem(
                        requiredLong(arguments, "routineId"),
                        requiredLong(arguments, "routineItemId"),
                        new RoutineRequests.PatchRoutineItem(
                                optionalText(arguments, "title"),
                                optionalText(arguments, "content"),
                                optionalDecimal(arguments, "targetValue"),
                                optionalText(arguments, "targetUnit"),
                                optionalInteger(arguments, "sets"),
                                optionalInteger(arguments, "restSeconds"),
                                optionalText(arguments, "memo"),
                                optionalBoolean(arguments, "excludeFromAiAdjustment")));
        return item.getName() + " 항목을 변경했습니다.";
    }

    private String executePatchExercise(Map<String, Object> arguments) {
        ExerciseItem item =
                routineService.patchExercise(
                        requiredLong(arguments, "routineId"),
                        requiredLong(arguments, "exerciseId"),
                        new RoutineRequests.PatchExercise(
                                optionalText(arguments, "name"),
                                optionalDecimal(arguments, "targetValue"),
                                optionalText(arguments, "targetUnit"),
                                optionalInteger(arguments, "sets"),
                                optionalInteger(arguments, "restSeconds"),
                                null,
                                null,
                                optionalText(arguments, "memo"),
                                optionalBoolean(arguments, "excludeFromAiAdjustment")));
        return item.getName() + " 항목을 변경했습니다.";
    }

    private String executePatchRoutine(Map<String, Object> arguments) {
        Routine routine =
                routineService.patch(
                        requiredLong(arguments, "routineId"),
                        new RoutineRequests.PatchRoutine(
                                optionalText(arguments, "title"),
                                optionalText(arguments, "description"),
                                optionalDate(arguments, "endDate"),
                                optionalBoolean(arguments, "aiAdjustmentAllowed"),
                                optionalText(arguments, "status")));
        return routine.getTitle() + " 루틴을 변경했습니다.";
    }

    private String executeAdjust(
            PendingAiAction action, Map<String, Object> arguments, int operationIndex) {
        Long routineId = requiredLong(arguments, "routineId");
        AiJob job =
                routineService.adjust(
                        routineId,
                        new RoutineRequests.AdjustmentRequest(
                                requiredText(arguments, "reason", 500),
                                optionalText(arguments, "userMessage")),
                        "chat-action-" + action.getId() + "-" + operationIndex);
        return "루틴 재조정 작업을 요청했습니다. 작업 ID: " + job.getId();
    }

    private String executeRecord(Map<String, Object> arguments) {
        Map<String, Object> conditionMap = optionalMap(arguments, "condition");
        RecordRequest.Condition condition =
                conditionMap == null
                        ? null
                        : new RecordRequest.Condition(
                                optionalInteger(conditionMap, "energyLevel"),
                                optionalInteger(conditionMap, "painLevel"),
                                optionalText(conditionMap, "memo"));
        ActivityRecord record =
                recordService.create(
                        new RecordRequest(
                                optionalLong(arguments, "routineItemId"),
                                enumValue(
                                        ActivityType.class,
                                        requiredText(arguments, "type", 30),
                                        "활동 유형"),
                                OffsetDateTime.now(ZoneId.of("Asia/Seoul")),
                                Optional.ofNullable(optionalMap(arguments, "details"))
                                        .orElseGet(Map::of),
                                null,
                                condition));
        return record.getType() + " 기록을 저장했습니다.";
    }

    private String executeCreateRoutine(Map<String, Object> arguments) {
        Routine routine = routineService.createGeneratedRoutine(createRoutineRequest(arguments));
        return routine.getTitle() + " 루틴을 생성했습니다. 루틴 ID: " + routine.getId();
    }

    private String executePersonalizeCurriculum(Map<String, Object> arguments) {
        Routine routine =
                routineService.personalizeCurriculum(curriculumPersonalizationRequest(arguments));
        return routine.getTitle() + " 개인화 루틴을 생성했습니다. 루틴 ID: " + routine.getId();
    }

    private String executeMealCart(Map<String, Object> arguments) {
        Map<String, Object> cart =
                expansionService.cart(
                        requiredLong(arguments, "routineId"),
                        requiredText(arguments, "partner", 100),
                        requiredMapList(arguments, "items", 50));
        return "장바구니를 생성했습니다. 장바구니 ID: " + cart.get("cartId");
    }

    private RoutineRequests.ChatGenerationRequest createRoutineRequest(
            Map<String, Object> arguments) {
        List<RoutineRequests.GeneratedRoutineItem> items = new ArrayList<>();
        for (Map<String, Object> item : requiredMapList(arguments, "items", 200)) {
            allowOnly(item, GENERATED_ITEM_KEYS);
            items.add(
                    new RoutineRequests.GeneratedRoutineItem(
                            requiredInteger(item, "dayOffset"),
                            requiredText(item, "sectionType", 40),
                            requiredText(item, "sectionTitle", 100),
                            requiredText(item, "itemType", 30),
                            requiredText(item, "title", 200),
                            optionalText(item, "content"),
                            optionalText(item, "scheduledTime"),
                            requiredDecimal(item, "targetValue"),
                            requiredText(item, "targetUnit", 30),
                            optionalInteger(item, "sets"),
                            optionalInteger(item, "restSeconds"),
                            optionalText(item, "memo")));
        }
        return new RoutineRequests.ChatGenerationRequest(
                requiredLong(arguments, "analysisId"),
                requiredText(arguments, "title", 200),
                optionalText(arguments, "goal"),
                requiredDate(arguments, "startDate"),
                requiredInteger(arguments, "durationWeeks"),
                items);
    }

    private RoutineRequests.CurriculumPersonalizationRequest curriculumPersonalizationRequest(
            Map<String, Object> arguments) {
        List<RoutineRequests.PersonalizedItem> replacements = new ArrayList<>();
        for (Map<String, Object> item : optionalMapList(arguments, "replacementItems", 100)) {
            allowOnly(item, PERSONALIZED_ITEM_KEYS);
            replacements.add(
                    new RoutineRequests.PersonalizedItem(
                            requiredLong(item, "sourceItemId"),
                            requiredText(item, "activityType", 30),
                            requiredText(item, "title", 200),
                            optionalText(item, "description"),
                            optionalInteger(item, "durationMinutes"),
                            Optional.ofNullable(optionalMap(item, "details")).orElseGet(Map::of)));
        }
        return new RoutineRequests.CurriculumPersonalizationRequest(
                requiredLong(arguments, "curriculumId"),
                requiredLong(arguments, "analysisId"),
                requiredDate(arguments, "startDate"),
                requiredInteger(arguments, "durationWeeks"),
                optionalLongList(arguments, "excludedItemIds", 100),
                replacements);
    }

    private Map<String, Object> readArguments(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 변경안을 읽지 못했습니다.");
        }
    }

    private List<AiOperation> readOperations(PendingAiAction action) {
        Map<String, Object> stored = readArguments(action.getArgumentsJson());
        if (!ACTION_PLAN.equals(action.getMethodName())) {
            return List.of(new AiOperation(action.getMethodName(), stored));
        }
        try {
            Object raw = stored.get("operations");
            if (raw == null) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 실행 계획이 없습니다.");
            }
            return json.convertValue(raw, new TypeReference<List<AiOperation>>() {});
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 실행 계획을 읽지 못했습니다.");
        }
    }

    private void allowOnly(Map<String, Object> values, Set<String> allowed) {
        if (!allowed.containsAll(values.keySet())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "AI 변경안에 허용되지 않은 인자가 포함되어 있습니다.");
        }
    }

    private void requireAnyChange(Map<String, Object> values, Set<String> idKeys) {
        boolean changed =
                values.entrySet().stream()
                        .anyMatch(
                                entry ->
                                        !idKeys.contains(entry.getKey())
                                                && entry.getValue() != null);
        if (!changed) throw new ApiException(HttpStatus.BAD_REQUEST, "실제로 변경할 값이 없습니다.");
    }

    private Long requiredLong(Map<String, Object> values, String key) {
        Long value = optionalLong(values, key);
        if (value == null || value <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
        }
        return value;
    }

    private Long optionalLong(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 정수여야 합니다.");
        }
    }

    private Integer optionalInteger(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 정수여야 합니다.");
        }
    }

    private Integer requiredInteger(Map<String, Object> values, String key) {
        Integer value = optionalInteger(values, key);
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
        }
        return value;
    }

    private BigDecimal optionalDecimal(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString());
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 숫자여야 합니다.");
        }
    }

    private BigDecimal requiredDecimal(Map<String, Object> values, String key) {
        BigDecimal value = optionalDecimal(values, key);
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
        }
        return value;
    }

    private Boolean optionalBoolean(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (value instanceof Boolean booleanValue) return booleanValue;
        if ("true".equalsIgnoreCase(value.toString())) return true;
        if ("false".equalsIgnoreCase(value.toString())) return false;
        throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 true 또는 false여야 합니다.");
    }

    private String optionalText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString().trim();
    }

    private String requiredText(Map<String, Object> values, String key, int maxLength) {
        String value = optionalText(values, key);
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
        }
        if (value.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 너무 깁니다.");
        }
        return value;
    }

    private LocalDate optionalDate(Map<String, Object> values, String key) {
        String value = optionalText(values, key);
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 YYYY-MM-DD 형식이어야 합니다.");
        }
    }

    private LocalDate requiredDate(Map<String, Object> values, String key) {
        LocalDate value = optionalDate(values, key);
        if (value == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
        }
        return value;
    }

    private Map<String, Object> optionalMap(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        try {
            return json.convertValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 객체여야 합니다.");
        }
    }

    private List<Map<String, Object>> requiredMapList(
            Map<String, Object> values, String key, int maximum) {
        List<Map<String, Object>> result = optionalMapList(values, key, maximum);
        if (result.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 필요합니다.");
        }
        return result;
    }

    private List<Map<String, Object>> optionalMapList(
            Map<String, Object> values, String key, int maximum) {
        Object value = values.get(key);
        if (value == null) return List.of();
        try {
            List<Map<String, Object>> result = json.convertValue(value, new TypeReference<>() {});
            if (result.size() > maximum) {
                throw new ApiException(HttpStatus.BAD_REQUEST, key + " 항목이 너무 많습니다.");
            }
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 객체 배열이어야 합니다.");
        }
    }

    private List<Long> optionalLongList(Map<String, Object> values, String key, int maximum) {
        Object value = values.get(key);
        if (value == null) return List.of();
        try {
            List<Object> raw = json.convertValue(value, new TypeReference<>() {});
            if (raw.size() > maximum) {
                throw new ApiException(HttpStatus.BAD_REQUEST, key + " 항목이 너무 많습니다.");
            }
            List<Long> result = new ArrayList<>();
            for (Object item : raw) {
                long id = Long.parseLong(String.valueOf(item));
                if (id <= 0) throw new NumberFormatException();
                result.add(id);
            }
            return result;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값은 양의 정수 배열이어야 합니다.");
        }
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, label + " 값이 올바르지 않습니다.");
        }
    }

    private void decimalRange(
            Map<String, Object> values, String key, BigDecimal minimum, BigDecimal maximum) {
        BigDecimal value = optionalDecimal(values, key);
        if (value != null && (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 허용 범위를 벗어났습니다.");
        }
    }

    private void integerRange(Map<String, Object> values, String key, int minimum, int maximum) {
        Integer value = optionalInteger(values, key);
        if (value != null && (value < minimum || value > maximum)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 허용 범위를 벗어났습니다.");
        }
    }

    private void nonBlankText(Map<String, Object> values, String key, int maximum) {
        if (!values.containsKey(key) || values.get(key) == null) return;
        String value = optionalText(values, key);
        if (value.isBlank() || value.length() > maximum) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 올바르지 않습니다.");
        }
    }

    private void maxText(Map<String, Object> values, String key, int maximum) {
        String value = optionalText(values, key);
        if (value != null && value.length() > maximum) {
            throw new ApiException(HttpStatus.BAD_REQUEST, key + " 값이 너무 깁니다.");
        }
    }
}
