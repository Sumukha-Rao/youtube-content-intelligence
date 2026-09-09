package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.security.CurrentUser;
import com.yci.service.ChannelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** The creator's own channel — a user has at most one. */
@RestController
@RequestMapping("/api/channel")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    public Dto.ChannelDto get(@CurrentUser Long userId) {
        return channelService.getChannelDto(userId);
    }

    /** Set or replace the channel. Accepts an id, @handle or any channel URL. */
    @PutMapping
    public Dto.ChannelDto set(@CurrentUser Long userId, @Valid @RequestBody Dto.CreateChannelRequest req) {
        return channelService.setChannel(userId, req.channelUrl());
    }

    @DeleteMapping
    public void remove(@CurrentUser Long userId) {
        channelService.removeChannel(userId);
    }
}
