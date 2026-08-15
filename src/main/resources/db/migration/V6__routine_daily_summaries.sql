-- 일차별 식단·운동 핵심 제목은 독립 생명주기가 없는 루틴 표시 속성이므로 JSON으로 보관한다.
ALTER TABLE personalized_routines
    ADD COLUMN daily_summaries JSON NULL AFTER description;
