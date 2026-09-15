package uk.gov.cshr.notificationservice.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.cshr.notificationservice.domain.EmailTemplate;
import uk.gov.cshr.notificationservice.dto.FailedResource;
import uk.gov.cshr.notificationservice.dto.email.BulkSendEmailResponse;
import uk.gov.cshr.notificationservice.dto.email.MessageDto;
import uk.gov.cshr.notificationservice.dto.email.NamedMessageDto;
import uk.gov.cshr.notificationservice.dto.email.TemplatedMessageDto;
import uk.gov.cshr.notificationservice.exception.EmailTemplateNotFound;
import uk.gov.cshr.notificationservice.exception.NotificationServiceException;
import uk.gov.cshr.notificationservice.repository.EmailTemplatesRepository;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final NotificationClient client;
    private final EmailTemplatesRepository emailTemplatesRepository;

    public SendEmailResponse send(TemplatedMessageDto message) {
        int attempt = 0;
        int[] attemptDelaysInMilliseconds = {0, 1000, 2000, 4000};

        while (true) {
            log.debug("Attempt #{} to send email. Sending in {} milliseconds.", attempt+1, attemptDelaysInMilliseconds[attempt]);
            try {
                Thread.sleep(attemptDelaysInMilliseconds[attempt]);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            try {
                SendEmailResponse response = client.sendEmail(
                        message.getTemplateId(),
                        message.getRecipient(),
                        message.getPersonalisation(),
                        message.getReference()
                );
                log.info("Notify email with ID {} and content \n{}\n has been sent", response.getNotificationId(), response.getBody());
                return response;
            } catch (NotificationClientException e) {
                log.debug("Attempt failed. Reason: {}", e.getMessage());
                attempt++;

                if(attempt == attemptDelaysInMilliseconds.length) {
                    throw new NotificationServiceException("Unable to send message", e);
                }
            }
        }
    }

    public void send(String emailName, MessageDto message) {
        EmailTemplate emailTemplate = emailTemplatesRepository.findById(emailName).orElseThrow(() -> new EmailTemplateNotFound(emailName));
        TemplatedMessageDto templatedMessageDto = new TemplatedMessageDto(message.getPersonalisation(), message.getRecipient(), message.getReference(), emailTemplate.getExternalTemplateId());
        send(templatedMessageDto);
    }

    public BulkSendEmailResponse send(List<NamedMessageDto> messages) {
        log.info("Sending {} emails", messages.size());
        List<String> successfulIds = new ArrayList<>();
        List<FailedResource<NamedMessageDto>> failedEmails = new ArrayList<>();
        Map<String, String> templateMap = emailTemplatesRepository.findAllById(messages.stream().map(NamedMessageDto::getName).toList())
                .stream().collect(Collectors.toMap(EmailTemplate::getEmailTemplateName, EmailTemplate::getExternalTemplateId));
        for (NamedMessageDto message : messages) {
            String templateId = templateMap.get(message.getName());
            if (templateId == null) {
                FailedResource<NamedMessageDto> failedResource = new FailedResource<>(message, new EmailTemplateNotFound(message.getName()).getMessage());
                failedEmails.add(failedResource);
            } else {
                TemplatedMessageDto templatedMessageDto = new TemplatedMessageDto(message.getPersonalisation(), message.getRecipient(), message.getReference(), templateId);
                try {
                    SendEmailResponse response = send(templatedMessageDto);
                    successfulIds.add(response.getNotificationId().toString());
                } catch (NotificationServiceException e) {
                    failedEmails.add(new FailedResource<>(message, e.getMessage()));
                }
            }
        }
        return new BulkSendEmailResponse(successfulIds, failedEmails);
    }

}
