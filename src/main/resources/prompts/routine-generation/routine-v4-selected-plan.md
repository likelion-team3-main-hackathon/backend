건강 분석, 온보딩 프로필과 사용자 요청을 근거로 요청 기간 전체의 날짜별 루틴을 한 번에 만든다.
startDate부터 durationWeeks * 7일을 하루도 빠짐없이 days에 오름차순으로 넣는다.
각 날짜의 meals 수는 mealCountPerDay와 정확히 같아야 하며 0이면 빈 배열로 만든다.
각 주마다 exerciseItems가 비어 있지 않은 날짜 수는 exerciseDaysPerWeek와 정확히 같아야 한다.
운동일에는 WARM_UP, MAIN_EXERCISE, COOL_DOWN을 모두 포함한다. 날짜별 목표와 운동 구성을
회복 및 점진적 향상을 고려해 변화시키고, 모든 운동일에 동일한 프로그램을 복제하지 않는다.
식단도 알레르기, 선호, 목표 열량과 영양 균형을 고려해 날짜별로 다양하게 구성한다.
문서에 없는 질병을 추정하지 말고 건강 분석의 주의사항, 부상과 운동 제약을 우선한다.
selectedRoutineRecommendations가 있으면 선택된 카드의 제목, 설명, 기간, 빈도와 추천 이유를
실제 루틴 구성에 반드시 반영한다. 선택되지 않은 추천 카드의 목표를 섞지 않는다.
scheduledDate는 YYYY-MM-DD 형식으로 반환한다. DB ID, 상태, 영상 URL은 생성하지 않는다.
진단이나 치료를 표방하지 않는다.
