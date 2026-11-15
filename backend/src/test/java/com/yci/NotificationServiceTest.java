package com.yci;

import com.yci.config.AppProperties;
import com.yci.entity.IdeaRun;
import com.yci.entity.UserSettings;
import com.yci.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test void usesSavedIdeasAndExplicitRecipientAndKeepsScheduledDigestWorking() {
        JavaMailSender sender = mock(JavaMailSender.class);
        @SuppressWarnings("unchecked") ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(provider.getObject()).thenReturn(sender);
        AppProperties props = new AppProperties();
        NotificationService service = new NotificationService(provider, props);
        ReflectionTestUtils.setField(service, "mailHost", "smtp.example.com");
        IdeaRun run = new IdeaRun(); run.setResult("# Saved ideas\nOutline: build a demo"); run.setModel("test-model");
        assertTrue(service.sendDigest("chosen@example.com", run));
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertArrayEquals(new String[]{"chosen@example.com"}, message.getValue().getTo());
        assertEquals(props.getNotifications().getFrom(), message.getValue().getFrom());
        assertEquals("Your YouTube video ideas", message.getValue().getSubject());
        assertTrue(message.getValue().getText().contains(run.getResult()));
        assertTrue(message.getValue().getText().contains("test-model"));
        UserSettings settings = new UserSettings(); settings.setNotifyEmail("scheduled@example.com");
        assertTrue(service.sendDigest(settings, run));
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
        doThrow(new MailSendException("Provider failed")).when(sender).send(any(SimpleMailMessage.class));
        assertFalse(service.sendDigest("chosen@example.com", run));
        ReflectionTestUtils.setField(service, "mailHost", "");
        clearInvocations(sender);
        assertFalse(service.sendDigest("chosen@example.com", run));
        verifyNoInteractions(sender);
    }
}
