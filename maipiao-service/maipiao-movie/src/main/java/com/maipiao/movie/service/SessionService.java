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
     * @param showDate 不传时默认今天，这样还没做日期选择器的客户端拿到的仍是有用的
     *                 东西
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
