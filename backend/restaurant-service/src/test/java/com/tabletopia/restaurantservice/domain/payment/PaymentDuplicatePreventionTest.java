package com.tabletopia.restaurantservice.domain.payment;

import com.tabletopia.restaurantservice.domain.payment.controller.PaymentController;
import com.tabletopia.restaurantservice.domain.payment.dto.PaymentRequestDTO;
import com.tabletopia.restaurantservice.domain.payment.dto.ReservationPaymentRequestDTO;
import com.tabletopia.restaurantservice.domain.payment.entity.Payment;
import com.tabletopia.restaurantservice.domain.payment.service.PaymentService;
import com.tabletopia.restaurantservice.domain.reservation.dto.ReservationRequest;
import com.tabletopia.restaurantservice.domain.reservation.service.ReservationFacadeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * PaymentController 중복 결제 방지 단위 테스트
 *
 * 검증 대상: 동일한 orderNo로 동시에 여러 요청이 들어올 때
 *           결제 처리(createPayment)가 정확히 1번만 실행되는지 확인
 *
 * @author 김예진
 */
@ExtendWith(MockitoExtension.class)
class PaymentDuplicatePreventionTest {

    @InjectMocks
    PaymentController paymentController;

    @Mock
    PaymentService paymentService;

    @Mock
    ReservationFacadeService reservationFacadeService;

    @Mock
    Principal principal;

    /** 컨트롤러의 private static pendingPayments 맵에 테스트용 데이터를 직접 주입 */
    private Map<String, ReservationPaymentRequestDTO> pendingPayments;

    private static final String TEST_ORDER_NO = "test-order-12345";
    private static final int THREAD_COUNT = 15;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // PaymentController의 private static 필드 pendingPayments를 리플렉션으로 접근
        Field field = PaymentController.class.getDeclaredField("pendingPayments");
        field.setAccessible(true);
        pendingPayments = (Map<String, ReservationPaymentRequestDTO>) field.get(null);

        // 각 테스트 전 초기화 후 테스트용 주문 등록
        pendingPayments.clear();

        PaymentRequestDTO paymentRequestDTO = new PaymentRequestDTO();
        paymentRequestDTO.setOrderNo(TEST_ORDER_NO);
        paymentRequestDTO.setAmount(10000);
        paymentRequestDTO.setAmountTaxFree(10000);
        paymentRequestDTO.setProductDesc("테스트 레스토랑");

        ReservationRequest reservationRequest = new ReservationRequest();

        ReservationPaymentRequestDTO dto = new ReservationPaymentRequestDTO();
        dto.setPaymentRequestDTO(paymentRequestDTO);
        dto.setReservationRequest(reservationRequest);

        pendingPayments.put(TEST_ORDER_NO, dto);
    }

    // ------------------------------------------------------------------ //

    /**
     * 핵심 테스트: 15개 스레드가 동시에 같은 orderNo로 confirm 요청 시
     * createPayment 는 정확히 1번만 호출되어야 한다.
     */
    @Test
    @DisplayName("동시에 15개 요청이 들어와도 createPayment 는 1번만 실행된다.")
    void confirmPayment_concurrent15Requests_createPaymentCalledOnce() throws Exception {
        // given
        Payment mockPayment = new Payment();
        given(paymentService.createPayment(any())).willReturn(mockPayment);
        given(reservationFacadeService.registerReservationWithPayment(any(), any(), any()))
                .willReturn(Map.of("success", true));

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);   // 모든 스레드 동시 출발용
        CountDownLatch doneLatch  = new CountDownLatch(THREAD_COUNT);

        AtomicInteger successCount  = new AtomicInteger(0); // success=true 응답 수
        AtomicInteger alreadyCount  = new AtomicInteger(0); // "이미 처리된 결제" 응답 수
        AtomicInteger errorCount    = new AtomicInteger(0); // 500 오류 수

        // when
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 스레드가 준비될 때까지 대기

                    Map<String, Object> requestBody = Map.of(
                            "paymentConfirm", Map.of(
                                    "status",    "PAY_COMPLETE",
                                    "orderNo",   TEST_ORDER_NO,
                                    "payMethod", "TOSS-PAY"
                            )
                    );

                    ResponseEntity<Map<String, Object>> response =
                            paymentController.paymentConfirm(requestBody, principal);

                    Map<String, Object> body = response.getBody();
                    if (response.getStatusCode().is2xxSuccessful() && body != null) {
                        String message = (String) body.get("message");
                        if ("이미 처리된 결제입니다.".equals(message)) {
                            alreadyCount.incrementAndGet();
                        } else {
                            successCount.incrementAndGet();
                        }
                    } else {
                        errorCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 전 스레드 동시 출발
        boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then
        assertThat(finished).as("10초 내 모든 스레드 완료").isTrue();

        // createPayment 는 정확히 1번만 호출되어야 함
        verify(paymentService, times(1)).createPayment(any());

        // 성공 처리는 1번, 나머지는 "이미 처리된 결제"
        assertThat(successCount.get())
                .as("실제 결제 성공 응답은 1건이어야 한다")
                .isEqualTo(1);
        assertThat(alreadyCount.get())
                .as("나머지 %d건은 '이미 처리된 결제' 응답이어야 한다", THREAD_COUNT - 1)
                .isEqualTo(THREAD_COUNT - 1);
        assertThat(errorCount.get())
                .as("오류 응답은 0건이어야 한다")
                .isEqualTo(0);
    }

    // ------------------------------------------------------------------ //

    /**
     * PAY_COMPLETE 가 아닌 상태로 요청하면 오류를 반환해야 한다.
     */
    @Test
    @DisplayName("PAY_COMPLETE 가 아닌 status 로 요청하면 500 오류를 반환한다.")
    void confirmPayment_invalidStatus_returns500() {
        // given
        Map<String, Object> requestBody = Map.of(
                "paymentConfirm", Map.of(
                        "status",    "PAY_CANCEL",
                        "orderNo",   TEST_ORDER_NO,
                        "payMethod", "TOSS-PAY"
                )
        );

        // when
        ResponseEntity<Map<String, Object>> response =
                paymentController.paymentConfirm(requestBody, principal);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsKey("message");

        // 상태가 잘못되었을 때 createPayment 는 호출되지 않아야 함
        verify(paymentService, never()).createPayment(any());
    }

    /**
     * paymentConfirm 필드 자체가 없는 요청은 오류를 반환해야 한다.
     */
    @Test
    @DisplayName("paymentConfirm 필드가 없으면 500 오류를 반환한다.")
    void confirmPayment_missingPaymentConfirmField_returns500() {
        // given
        Map<String, Object> requestBody = Map.of("otherField", "value");

        // when
        ResponseEntity<Map<String, Object>> response =
                paymentController.paymentConfirm(requestBody, principal);

        // then
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        verify(paymentService, never()).createPayment(any());
    }

    /**
     * pendingPayments 에 없는 orderNo 로 요청하면 "이미 처리된 결제" 응답을 반환해야 한다.
     * (이미 처리되었거나 존재하지 않는 주문)
     */
    @Test
    @DisplayName("존재하지 않는 orderNo 로 요청하면 '이미 처리된 결제' 응답을 반환한다.")
    void confirmPayment_unknownOrderNo_returnsAlreadyProcessed() {
        // given
        Map<String, Object> requestBody = Map.of(
                "paymentConfirm", Map.of(
                        "status",    "PAY_COMPLETE",
                        "orderNo",   "not-existing-order",
                        "payMethod", "TOSS-PAY"
                )
        );

        // when
        ResponseEntity<Map<String, Object>> response =
                paymentController.paymentConfirm(requestBody, principal);

        // then
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("message", "이미 처리된 결제입니다.");
        verify(paymentService, never()).createPayment(any());
    }

    /**
     * 첫 번째 요청 성공 후 동일 orderNo 로 재요청하면 "이미 처리된 결제" 응답이어야 한다.
     * (순차 재시도 시나리오)
     */
    @Test
    @DisplayName("첫 번째 요청 성공 후 재요청하면 '이미 처리된 결제' 응답을 반환한다.")
    void confirmPayment_retryAfterSuccess_returnsAlreadyProcessed() {
        // given
        Payment mockPayment = new Payment();
        given(paymentService.createPayment(any())).willReturn(mockPayment);
        given(reservationFacadeService.registerReservationWithPayment(any(), any(), any()))
                .willReturn(Map.of("success", true));

        Map<String, Object> requestBody = Map.of(
                "paymentConfirm", Map.of(
                        "status",    "PAY_COMPLETE",
                        "orderNo",   TEST_ORDER_NO,
                        "payMethod", "TOSS-PAY"
                )
        );

        // when - 첫 번째 요청
        ResponseEntity<Map<String, Object>> first =
                paymentController.paymentConfirm(requestBody, principal);
        // when - 두 번째 요청 (재시도)
        ResponseEntity<Map<String, Object>> second =
                paymentController.paymentConfirm(requestBody, principal);

        // then
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(first.getBody()).doesNotContainEntry("message", "이미 처리된 결제입니다.");

        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getBody())
                .containsEntry("message", "이미 처리된 결제입니다.");

        // createPayment 는 첫 번째 요청에서만 1번 호출
        verify(paymentService, times(1)).createPayment(any());
    }
}
