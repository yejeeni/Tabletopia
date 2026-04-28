@echo off
REM ====================================
REM 레스토랑 예약 시스템 부하 테스트 실행 스크립트
REM ====================================

echo ====================================
echo Redis 캐싱 성능 부하 테스트 시작
echo ====================================
echo.

REM 결과 디렉토리 생성
if not exist "results" mkdir results
if not exist "results\html-report" mkdir results\html-report

REM 기존 결과 파일 백업
if exist "results\test-results.jtl" (
    echo 기존 결과 파일 백업 중...
    move /Y results\test-results.jtl results\test-results-backup-%date:~0,4%%date:~5,2%%date:~8,2%-%time:~0,2%%time:~3,2%%time:~6,2%.jtl
)

echo.
echo [1/3] 부하 테스트 실행 중...
echo - 시나리오 1: 타임슬롯 조회 (100명 동시 접속, 10회 반복)
echo - 시나리오 2: 동시 테이블 선점 (100명 동시 선점)
echo - 시나리오 3: 스트레스 테스트 (500명, 60초 램프업)
echo.

REM JMeter 실행
jmeter -n -t reservation-load-test.jmx -l results\test-results.jtl -e -o results\html-report

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [오류] 테스트 실행 실패!
    echo JMeter가 설치되어 있는지 확인하세요.
    echo 설치 방법: choco install jmeter
    pause
    exit /b 1
)

echo.
echo [2/3] 결과 분석 중...
echo.

REM 요약 정보 출력
echo ====================================
echo 테스트 완료!
echo ====================================
echo.
echo 결과 파일 위치:
echo - JTL 파일: %CD%\results\test-results.jtl
echo - HTML 리포트: %CD%\results\html-report\index.html
echo.

echo [3/3] HTML 리포트 열기...
start results\html-report\index.html

echo.
echo 애플리케이션 로그에서 성능 측정 결과 확인:
echo   findstr "[성능측정]" ..\logs\application.log
echo.

pause
