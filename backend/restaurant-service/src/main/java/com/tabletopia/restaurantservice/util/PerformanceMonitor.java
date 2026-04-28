package com.tabletopia.restaurantservice.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 성능 측정 유틸리티
 * Redis 캐시 성능 측정을 위한 응답 시간 추적
 *
 * @author 김예진
 * @since 2025-10-21
 */
@Slf4j
@Component
public class PerformanceMonitor {

  /**
   * 응답 시간 측정 및 로그 출력
   *
   * @param operation 작업 이름
   * @param runnable 실행할 작업
   */
  public void measureTime(String operation, Runnable runnable) {
    long startTime = System.nanoTime();
    try {
      runnable.run();
    } finally {
      long endTime = System.nanoTime();
      double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
      log.info("[성능측정] {} - 소요시간: {:.2f}ms", operation, elapsedTimeMs);
    }
  }

  /**
   * 응답 시간 측정 및 결과 반환
   *
   * @param operation 작업 이름
   * @param supplier 실행할 작업 (결과 반환)
   * @param <T> 반환 타입
   * @return 작업 결과
   */
  public <T> T measureTimeWithResult(String operation, java.util.function.Supplier<T> supplier) {
    long startTime = System.nanoTime();
    try {
      T result = supplier.get();
      long endTime = System.nanoTime();
      double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
      log.info("[성능측정] {} - 소요시간: {:.2f}ms", operation, elapsedTimeMs);
      return result;
    } catch (Exception e) {
      long endTime = System.nanoTime();
      double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
      log.error("[성능측정] {} - 소요시간: {:.2f}ms (오류 발생)", operation, elapsedTimeMs, e);
      throw e;
    }
  }

  /**
   * 캐시 히트/미스 로그
   *
   * @param operation 작업 이름
   * @param cacheHit 캐시 히트 여부
   * @param elapsedTimeMs 소요 시간 (ms)
   */
  public void logCachePerformance(String operation, boolean cacheHit, double elapsedTimeMs) {
    if (cacheHit) {
      log.info("[성능측정][캐시HIT] {} - 소요시간: {:.2f}ms", operation, elapsedTimeMs);
    } else {
      log.info("[성능측정][캐시MISS] {} - 소요시간: {:.2f}ms (DB 조회)", operation, elapsedTimeMs);
    }
  }
}
