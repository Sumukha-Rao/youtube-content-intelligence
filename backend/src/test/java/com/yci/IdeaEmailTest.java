package com.yci;

import com.yci.controller.IdeaController;
import com.yci.entity.IdeaRun;
import com.yci.entity.RunStatus;
import com.yci.exception.GlobalExceptionHandler;
import com.yci.repository.IdeaRunRepository;
import com.yci.security.CurrentUser;
import com.yci.service.IdeaService;
import com.yci.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IdeaEmailTest {
    private final IdeaRunRepository runs = mock(IdeaRunRepository.class);
    private final NotificationService mail = mock(NotificationService.class);
    private MockMvc mvc;
    private IdeaRun run;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new IdeaController(mock(IdeaService.class), runs, mail))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter p) { return p.hasParameterAnnotation(CurrentUser.class); }
                    public Object resolveArgument(MethodParameter p, ModelAndViewContainer m, NativeWebRequest r,
                                                  WebDataBinderFactory b) { return 7L; }
                }).build();
        run = new IdeaRun();
        run.setUserId(7L); run.setStatus(RunStatus.COMPLETED); run.setResult("# Saved ideas");
        when(runs.findById(5L)).thenReturn(Optional.of(run));
        when(mail.isConfigured()).thenReturn(true);
        when(mail.sendDigest("recipient@example.com", run)).thenReturn(true);
    }

    @Test void sendsSavedRunToChosenRecipient() throws Exception {
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Ideas sent to recipient@example.com."));
        verify(mail).sendDigest("recipient@example.com", run);
        verify(runs, never()).save(any());
    }

    @Test void validatesRecipientBeforeSending() throws Exception {
        for (String address : new String[]{"", "invalid", "a@example.com,b@example.com", "Name <a@example.com>"}) {
            mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                    .content("{\"email\":\"" + address + "\"}")) .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(mail);
    }

    @Test void rejectsOtherUsersRunAndMissingRun() throws Exception {
        run.setUserId(99L);
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}")).andExpect(status().isNotFound());
        when(runs.findById(5L)).thenReturn(Optional.empty());
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}")).andExpect(status().isNotFound());
        verifyNoInteractions(mail);
    }

    @Test void rejectsUnfinishedOrEmptyRuns() throws Exception {
        run.setStatus(RunStatus.RUNNING);
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}")).andExpect(status().isBadRequest());
        run.setStatus(RunStatus.COMPLETED); run.setResult(" ");
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}")).andExpect(status().isBadRequest());
        verifyNoInteractions(mail);
    }

    @Test void reportsMissingSmtpAndDeliveryFailure() throws Exception {
        when(mail.isConfigured()).thenReturn(false);
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}")).andExpect(status().isServiceUnavailable());
        verify(mail, never()).sendDigest(anyString(), any());
        when(mail.isConfigured()).thenReturn(true);
        when(mail.sendDigest("recipient@example.com", run)).thenReturn(false);
        mvc.perform(post("/api/ideas/runs/5/email").contentType("application/json")
                .content("{\"email\":\"recipient@example.com\"}")).andExpect(status().isBadGateway());
    }
}
