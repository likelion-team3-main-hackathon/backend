-- Complete the chatbot's question coverage without modifying previous Flyway files.
-- Only explicitly named high-intensity items are labelled. Unknown intensity stays NULL.
UPDATE routine_items
SET intensity = CASE
    WHEN UPPER(CONCAT(COALESCE(title, ''), ' ', COALESCE(content, ''))) LIKE '%HIIT%'
      OR CONCAT(COALESCE(title, ''), ' ', COALESCE(content, '')) LIKE '%고강도%'
      OR CONCAT(COALESCE(title, ''), ' ', COALESCE(content, '')) LIKE '%전력%'
      THEN 'HIGH'
    WHEN CONCAT(COALESCE(title, ''), ' ', COALESCE(content, '')) LIKE '%저강도%'
      OR CONCAT(COALESCE(title, ''), ' ', COALESCE(content, '')) LIKE '%가벼운%'
      THEN 'LOW'
    ELSE intensity
END
WHERE intensity IS NULL;