package com.accsaber.backend.service.discord;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.accsaber.backend.exception.ConflictException;
import com.accsaber.backend.exception.ResourceNotFoundException;
import com.accsaber.backend.model.dto.request.discord.LinkDiscordRequest;
import com.accsaber.backend.model.dto.request.discord.UpdateDiscordLinkRequest;
import com.accsaber.backend.model.dto.response.DiscordLinkResponse;
import com.accsaber.backend.model.entity.user.DiscordUserLink;
import com.accsaber.backend.model.entity.user.User;
import com.accsaber.backend.repository.user.DiscordUserLinkRepository;
import com.accsaber.backend.repository.user.UserRepository;
import com.accsaber.backend.service.player.DuplicateUserService;
import com.accsaber.backend.util.ProfileUrlResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordLinkService {

    private final DiscordUserLinkRepository discordUserLinkRepository;
    private final UserRepository userRepository;
    private final DuplicateUserService duplicateUserService;
    private final ProfileUrlResolver profileUrlResolver;

    @Transactional
    public DiscordLinkResponse link(LinkDiscordRequest request) {
        if (discordUserLinkRepository.existsById(request.getDiscordId())) {
            throw new ConflictException("Discord account is already linked", request.getDiscordId());
        }

        String steamId = profileUrlResolver.resolve(request.getProfileUrl());
        Long userId = duplicateUserService.resolvePrimaryUserId(Long.parseLong(steamId));

        if (discordUserLinkRepository.existsByUserId(userId)) {
            throw new ConflictException("Player is already linked to a Discord account", userId);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        DiscordUserLink link = DiscordUserLink.builder()
                .discordId(request.getDiscordId())
                .user(user)
                .build();
        discordUserLinkRepository.save(link);

        log.info("Linked Discord {} to user {}", request.getDiscordId(), userId);

        return toResponse(link);
    }

    @Transactional(readOnly = true)
    public DiscordLinkResponse findByDiscordId(String discordId) {
        DiscordUserLink link = discordUserLinkRepository.findById(discordId)
                .orElseThrow(() -> new ResourceNotFoundException("Discord link", discordId));
        return toResponse(link);
    }

    @Transactional(readOnly = true)
    public DiscordLinkResponse findByUserId(Long userId) {
        Long resolved = duplicateUserService.resolvePrimaryUserId(userId);
        DiscordUserLink link = discordUserLinkRepository.findByUserId(resolved)
                .orElseThrow(() -> new ResourceNotFoundException("Discord link for user", resolved));
        return toResponse(link);
    }

    @Transactional
    public void unlink(String discordId) {
        if (!discordUserLinkRepository.existsById(discordId)) {
            throw new ResourceNotFoundException("Discord link", discordId);
        }
        discordUserLinkRepository.deleteById(discordId);
        log.info("Unlinked Discord {}", discordId);
    }

    @Transactional
    public DiscordLinkResponse update(String discordId, UpdateDiscordLinkRequest request) {
        DiscordUserLink link = discordUserLinkRepository.findById(discordId)
                .orElseThrow(() -> new ResourceNotFoundException("Discord link", discordId));

        String steamId = profileUrlResolver.resolve(request.getProfileUrl());
        Long newUserId = duplicateUserService.resolvePrimaryUserId(Long.parseLong(steamId));

        if (!newUserId.equals(link.getUser().getId())
                && discordUserLinkRepository.existsByUserId(newUserId)) {
            throw new ConflictException("Player is already linked to a Discord account", newUserId);
        }

        User newUser = userRepository.findById(newUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", newUserId));
        Long oldUserId = link.getUser().getId();
        link.setUser(newUser);
        discordUserLinkRepository.save(link);

        log.info("Updated Discord {} link from user {} to user {}", discordId, oldUserId, newUserId);

        return toResponse(link);
    }

    private DiscordLinkResponse toResponse(DiscordUserLink link) {
        return DiscordLinkResponse.builder()
                .discordId(link.getDiscordId())
                .userId(String.valueOf(link.getUser().getId()))
                .playerName(link.getUser().getName())
                .createdAt(link.getCreatedAt())
                .build();
    }
}
