package tri_lion.health.service.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tri_lion.health.domain.expert.CurriculumType;
import tri_lion.health.dto.request.expert.ExpertContentRequest;
import tri_lion.health.exception.ApiException;
import tri_lion.health.security.AuthenticatedUser;

@Service
public class ExpansionService {
    private final JdbcTemplate db;
    private final AuthenticatedUser auth;
    private final ObjectMapper json;

    public ExpansionService(JdbcTemplate db, AuthenticatedUser auth, ObjectMapper json) {
        this.db = db;
        this.auth = auth;
        this.json = json;
    }

    @Transactional
    public Long apply(String type, List<String> specialties, String intro) {
        Long userId = auth.active().getId();
        if (db.queryForObject("select count(*) from experts where user_id=?", Integer.class, userId)
                > 0) {
            throw ApiException.conflict("이미 전문가 인증을 신청했습니다.");
        }
        db.update(
                "insert into experts(user_id,specialty,qualification_info,introduction,verification_status,applied_at) values(?,?,?,?,?,?)",
                userId,
                String.join(",", specialties),
                "certificate files stored privately",
                intro,
                "PENDING_REVIEW",
                Instant.now());
        return userId;
    }

    @Transactional
    public Long content(ExpertContentRequest request) {
        Long userId = auth.active().getId();
        String status =
                db.query(
                        "select verification_status from experts where user_id=?",
                        result -> result.next() ? result.getString(1) : null,
                        userId);
        if (!"APPROVED".equals(status)) {
            throw ApiException.forbidden("인증된 전문가만 콘텐츠를 등록할 수 있습니다.");
        }
        validateCurriculumType(request);

        KeyHolder key = new GeneratedKeyHolder();
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "insert into market_items(name,item_type,price,provider_name,status) values(?,?,?,?,?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, request.title());
                    statement.setString(2, "CURRICULUM");
                    statement.setLong(3, request.price());
                    statement.setString(4, "TRI_LION");
                    statement.setString(5, "DRAFT");
                    return statement;
                },
                key);
        Long curriculumId = Objects.requireNonNull(key.getKey()).longValue();

        db.update(
                "insert into curricula(market_item_id,expert_id,category,curriculum_type,difficulty,duration_days,description) values(?,?,?,?,?,?,?)",
                curriculumId,
                userId,
                request.category(),
                request.contentType().name(),
                request.difficulty(),
                request.durationWeeks() * 7,
                request.description());

        for (ExpertContentRequest.Item item : request.items()) {
            try {
                db.update(
                        "insert into curriculum_items(curriculum_id,week_number,sort_order,activity_type,title,description,scheduled_time,duration_minutes,details_json,media_url,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                        curriculumId,
                        item.week(),
                        item.order(),
                        item.activityType().name(),
                        item.title(),
                        item.description(),
                        item.scheduledTime(),
                        item.durationMinutes(),
                        json.writeValueAsString(item.details() == null ? Map.of() : item.details()),
                        item.mediaUrl(),
                        Instant.now());
            } catch (Exception exception) {
                throw new IllegalArgumentException("커리큘럼 항목을 저장할 수 없습니다.", exception);
            }
        }
        return curriculumId;
    }

    private void validateCurriculumType(ExpertContentRequest request) {
        Set<String> itemTypes = new HashSet<>();
        request.items().forEach(item -> itemTypes.add(item.activityType().name()));
        if (request.contentType() != CurriculumType.MIXED
                && (itemTypes.size() != 1 || !itemTypes.contains(request.contentType().name()))) {
            throw new IllegalArgumentException("단일 유형 커리큘럼의 모든 항목은 contentType과 같아야 합니다.");
        }
        if (request.contentType() == CurriculumType.MIXED && itemTypes.size() < 2) {
            throw new IllegalArgumentException("MIXED 커리큘럼에는 서로 다른 액티비티 타입이 2개 이상 필요합니다.");
        }
    }

    public List<Map<String, Object>> contents() {
        return db.queryForList(
                "select m.market_item_id id,m.name,m.price,m.image_url thumbnailUrl,c.category,c.curriculum_type contentType,c.difficulty,u.name expertName from market_items m join curricula c on c.market_item_id=m.market_item_id join users u on u.user_id=c.expert_id where m.status in ('ACTIVE','PUBLISHED')");
    }

    public Map<String, Object> content(Long id) {
        Map<String, Object> result =
                new LinkedHashMap<>(
                        db.queryForMap(
                                "select m.market_item_id id,m.name,m.price,m.image_url thumbnailUrl,c.category,c.curriculum_type contentType,c.difficulty,c.description,u.name expertName from market_items m join curricula c on c.market_item_id=m.market_item_id join users u on u.user_id=c.expert_id where m.market_item_id=?",
                                id));
        result.put(
                "items",
                db.queryForList(
                        "select curriculum_item_id itemId,week_number week,sort_order `order`,activity_type activityType,title,description,scheduled_time scheduledTime,duration_minutes durationMinutes,details_json details,media_url mediaUrl from curriculum_items where curriculum_id=? order by week_number,sort_order",
                        id));
        return result;
    }

    @Transactional
    public Long enroll(Long id, String access, boolean personalized) {
        Long userId = auth.active().getId();
        KeyHolder key = new GeneratedKeyHolder();
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "insert into enrollments(user_id,content_id,access_type,personalized,progress_rate,status,started_at) values(?,?,?,?,0,'ACTIVE',?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, userId);
                    statement.setLong(2, id);
                    statement.setString(3, access);
                    statement.setBoolean(4, personalized);
                    statement.setObject(5, LocalDate.now());
                    return statement;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    public List<Map<String, Object>> products() {
        auth.active();
        return db.queryForList(
                "select provider_name partner,market_item_id externalProductId,name,price,image_url imageUrl,purchase_url purchaseUrl from market_items where item_type='MEAL' and status='ACTIVE'");
    }

    @Transactional(readOnly = true)
    public void validateCart(
            Long routineId, String partner, List<Map<String, Object>> requestedItems) {
        Long userId = auth.active().getId();
        validateRoutineOwner(userId, routineId);
        normalizeCartItems(partner, requestedItems);
    }

    @Transactional
    public Map<String, Object> cart(
            Long routineId, String partner, List<Map<String, Object>> requestedItems) {
        Long userId = auth.active().getId();
        validateRoutineOwner(userId, routineId);
        List<Map<String, Object>> items = normalizeCartItems(partner, requestedItems);
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(1800);
        String checkoutUrl = "https://partner.example.com/deep-link";

        KeyHolder key = new GeneratedKeyHolder();
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(
                                    "insert into meal_carts(user_id,personalized_routine_id,partner,status,checkout_url,expires_at,created_at) values(?,?,?,'ACTIVE',?,?,?)",
                                    Statement.RETURN_GENERATED_KEYS);
                    statement.setLong(1, userId);
                    if (routineId == null) statement.setNull(2, java.sql.Types.BIGINT);
                    else statement.setLong(2, routineId);
                    statement.setString(3, partner.trim());
                    statement.setString(4, checkoutUrl);
                    statement.setObject(5, expiresAt);
                    statement.setObject(6, createdAt);
                    return statement;
                },
                key);
        Long cartId = Objects.requireNonNull(key.getKey()).longValue();

        for (Map<String, Object> item : items) {
            db.update(
                    "insert into meal_cart_items(meal_cart_id,market_item_id,quantity,unit_price,created_at) values(?,?,?,?,?)",
                    cartId,
                    item.get("marketItemId"),
                    item.get("quantity"),
                    item.get("unitPrice"),
                    createdAt);
        }

        return Map.of(
                "cartId",
                cartId,
                "routineId",
                routineId == null ? "" : routineId,
                "partner",
                partner.trim(),
                "checkoutUrl",
                checkoutUrl,
                "items",
                items,
                "expiresAt",
                expiresAt);
    }

    private void validateRoutineOwner(Long userId, Long routineId) {
        if (routineId == null) return;
        List<Map<String, Object>> routines =
                db.queryForList(
                        "select status,type from personalized_routines where personalized_routine_id=? and user_id=? and deleted_at is null",
                        routineId,
                        userId);
        if (routines.isEmpty()) {
            throw ApiException.notFound("장바구니를 구성할 식단 루틴을 찾을 수 없습니다.");
        }
        Map<String, Object> routine = routines.getFirst();
        if (!"ACTIVE".equalsIgnoreCase(value(routine, "status"))) {
            throw ApiException.conflict("현재 진행 중인 식단 루틴만 장바구니로 구성할 수 있습니다.");
        }
        String type = value(routine, "type");
        if (!Set.of("MEAL", "MIXED").contains(type.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("식단이 포함된 루틴만 장바구니로 구성할 수 있습니다.");
        }
        Integer mealCount =
                db.queryForObject(
                        "select count(*) from routine_items where personalized_routine_id=? and item_type='MEAL' and deleted_at is null",
                        Integer.class,
                        routineId);
        if (mealCount == null || mealCount == 0) {
            throw new IllegalArgumentException("루틴에 장바구니로 구성할 식단 항목이 없습니다.");
        }
    }

    private String value(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return Objects.toString(entry.getValue(), "");
            }
        }
        return "";
    }

    private List<Map<String, Object>> normalizeCartItems(
            String partner, List<Map<String, Object>> requestedItems) {
        if (partner == null || partner.isBlank() || partner.length() > 100) {
            throw new IllegalArgumentException("제휴사 이름이 필요합니다.");
        }
        if (requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 50) {
            throw new IllegalArgumentException("장바구니 상품은 1~50개여야 합니다.");
        }

        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (Map<String, Object> item : requestedItems) {
            Object rawId =
                    item.containsKey("marketItemId")
                            ? item.get("marketItemId")
                            : item.get("externalProductId");
            Long marketItemId = positiveLong(rawId, "marketItemId");
            int quantity = positiveInteger(item.getOrDefault("quantity", 1), "quantity");
            if (quantity > 20) throw new IllegalArgumentException("상품 수량은 최대 20개입니다.");
            quantities.merge(marketItemId, quantity, Integer::sum);
            if (quantities.get(marketItemId) > 20) {
                throw new IllegalArgumentException("같은 상품의 합계 수량은 최대 20개입니다.");
            }
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        quantities.forEach(
                (marketItemId, quantity) -> {
                    List<Map<String, Object>> found =
                            db.query(
                                    "select market_item_id,name,price from market_items where market_item_id=? and provider_name=? and item_type='MEAL' and status in ('ACTIVE','PUBLISHED')",
                                    (resultSet, rowNumber) -> {
                                        Map<String, Object> product = new LinkedHashMap<>();
                                        product.put("marketItemId", resultSet.getLong(1));
                                        product.put("name", resultSet.getString(2));
                                        product.put("unitPrice", resultSet.getLong(3));
                                        return product;
                                    },
                                    marketItemId,
                                    partner.trim());
                    if (found.isEmpty()) {
                        throw ApiException.notFound("해당 제휴사에서 판매 중인 식단 상품을 찾을 수 없습니다.");
                    }
                    Map<String, Object> product = new LinkedHashMap<>(found.getFirst());
                    product.put("quantity", quantity);
                    normalized.add(product);
                });
        return normalized;
    }

    private Long positiveLong(Object value, String label) {
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (Exception exception) {
            throw new IllegalArgumentException(label + " 값이 올바르지 않습니다.");
        }
    }

    private int positiveInteger(Object value, String label) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (Exception exception) {
            throw new IllegalArgumentException(label + " 값이 올바르지 않습니다.");
        }
    }
}
