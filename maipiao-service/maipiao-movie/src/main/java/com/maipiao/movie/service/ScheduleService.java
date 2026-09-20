package com.maipiao.movie.service;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.movie.dto.ScheduleVO;
import com.maipiao.movie.mapper.ScheduleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleMapper scheduleMapper;

    /**
     * @param showDate defaults to today when omitted, so a client that has not
     *                 implemented date picker yet still gets something useful
     */
    public List<ScheduleVO> list(Long filmId, Long cinemaId, LocalDate showDate) {
        LocalDate target = showDate != null ? showDate : LocalDate.now();
        return scheduleMapper.selectScheduleList(filmId, cinemaId, target);
    }

    public ScheduleVO detail(Long scheduleId) {
        ScheduleVO schedule = scheduleMapper.selectScheduleDetail(scheduleId);
        if (schedule == null) {
            throw new BizException(ErrorCode.SCHEDULE_NOT_FOUND);
        }
        return schedule;
    }
}
