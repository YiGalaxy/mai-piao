package com.maipiao.movie.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.movie.dto.SessionVO;
import com.maipiao.movie.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionMapper sessionMapper;

    /**
     * @param showDate defaults to today when omitted, so a client that has not
     *                 implemented date picker yet still gets something useful
     */
    public List<SessionVO> list(Long projectId, Long venueId, LocalDate showDate) {
        LocalDate target = showDate != null ? showDate : LocalDate.now();
        return sessionMapper.selectScheduleList(projectId, venueId, target);
    }

    public SessionVO detail(Long sessionId) {
        SessionVO schedule = sessionMapper.selectScheduleDetail(sessionId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        return schedule;
    }
}
