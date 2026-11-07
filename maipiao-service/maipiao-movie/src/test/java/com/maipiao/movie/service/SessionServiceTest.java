package com.maipiao.movie.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.mapper.SessionMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionServiceTest {

    private SessionMapper sessionMapper;
    private SessionService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(SessionMapper.class);
        service = new SessionService(sessionMapper);
    }

    @Test
    void listDefaultsToTodayWhenDateIsMissing() {
        List<SessionVO> expected = List.of();
        when(sessionMapper.selectScheduleList(3L, 8L, LocalDate.now())).thenReturn(expected);

        List<SessionVO> actual = service.list(3L, 8L, null);

        assertSame(expected, actual);
        verify(sessionMapper).selectScheduleList(3L, 8L, LocalDate.now());
    }

    @Test
    void listUsesRequestedDate() {
        LocalDate date = LocalDate.of(2026, 11, 5);
        List<SessionVO> expected = List.of();
        when(sessionMapper.selectScheduleList(3L, 8L, date)).thenReturn(expected);

        assertSame(expected, service.list(3L, 8L, date));
        verify(sessionMapper).selectScheduleList(3L, 8L, date);
    }

    @Test
    void detailRejectsMissingSchedule() {
        when(sessionMapper.selectScheduleDetail(99L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class, () -> service.detail(99L));

        assertEquals(ErrorCode.SCHEDULE_NOT_FOUND.getCode(), exception.getCode());
    }
}
