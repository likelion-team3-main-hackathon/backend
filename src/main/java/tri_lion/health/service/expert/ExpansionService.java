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

    public Map<String, Object> cart(String partner, List<Map<String, Object>> items) {
        auth.active();
        return Map.of(
                "cartId",
                UUID.randomUUID().toString(),
                "partner",
                partner,
                "checkoutUrl",
                "https://partner.example.com/deep-link",
                "items",
                items,
                "expiresAt",
                Instant.now().plusSeconds(1800));
    }
}
