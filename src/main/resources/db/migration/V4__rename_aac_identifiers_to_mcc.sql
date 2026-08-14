-- 서비스 명칭을 AAC에서 MCC로 정정한다.
-- 기존 업로드 문서가 새 API enum과 호환되도록 저장된 타입도 함께 이전한다.
UPDATE health_documents
SET document_type = 'MCC_RESULT'
WHERE document_type = 'AAC_RESULT';
