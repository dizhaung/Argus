/*
 * Copyright (c) 2016, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of Salesforce.com nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
	 
package com.salesforce.dva.argus.service.mail;

import com.google.inject.Inject;
import com.salesforce.dva.argus.service.DefaultService;
import com.salesforce.dva.argus.service.MailService;
import com.salesforce.dva.argus.system.SystemConfiguration;
import com.salesforce.dva.argus.system.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import static com.salesforce.dva.argus.system.SystemAssert.requireArgument;
import static com.salesforce.dva.argus.system.SystemAssert.requireState;

/**
 * Default implementation of the email service.
 *
 * @author  Tom Valine (tvaline@salesforce.com)
 */
public class DefaultMailService extends DefaultService implements MailService {

    //~ Instance fields ******************************************************************************************************************************

    private final Logger _logger = LoggerFactory.getLogger(DefaultMailService.class);
    private final SystemConfiguration _config;

    //~ Constructors *********************************************************************************************************************************

    /**
     * Creates a new DefaultMailService object.
     *
     * @param  config  The system configuration. Cannot be null.
     */
    @Inject
    public DefaultMailService(SystemConfiguration config) {
    	super(config);
        requireArgument(config != null, "Configuration cannot be null.");
        _config = config;
    }

    //~ Methods **************************************************************************************************************************************

    private Properties getMailProperties() {
        Properties result = new Properties();

        result.put("mail.transport.protocol", _config.getValue(Property.EMAIL_SMTP_TRANSPORT_PROTOCOL.getName(), Property.EMAIL_SMTP_TRANSPORT_PROTOCOL.getDefaultValue()));
        result.put("mail.smtp.port",_config.getValue(Property.EMAIL_SMTP_PORT.getName(), Property.EMAIL_SMTP_PORT.getDefaultValue()));
        
        result.put("mail.smtp.auth", _config.getValue(Property.EMAIL_SMTP_AUTH.getName(), Property.EMAIL_SMTP_AUTH.getDefaultValue()));
        result.put("mail.smtp.starttls.enable",_config.getValue(Property.EMAIL_SMTP_STARTTTLS_ENABLED.getName(),Property.EMAIL_SMTP_STARTTTLS_ENABLED.getDefaultValue()));
        result.put("mail.smtp.starttls.required",_config.getValue(Property.EMAIL_SMTP_STARTTTLS_REQUIRED.getName(),Property.EMAIL_SMTP_STARTTTLS_REQUIRED.getDefaultValue()));
        
        return result;
    }

    @Override
    public boolean sendMessage(EmailContext context) {
        requireState(!isDisposed(), "Cannot call methods on a disposed service.");
        requireArgument(context.getRecipients() != null && !context.getRecipients().isEmpty(),
                "Recipients cannot be null or empty.");
        String contentType = (context.getContentType() == null || context.getContentType().isEmpty()) ?
                "text; charset=utf-8" : context.getContentType();

        MailService.Priority priority = (context.getEmailPriority() == null) ? Priority.NORMAL : context.getEmailPriority();

        if (Boolean.valueOf(_config.getValue(com.salesforce.dva.argus.system.SystemConfiguration.Property.EMAIL_ENABLED))) {
            try {
                Session session = Session.getInstance(getMailProperties());
                MimeMessage message = new MimeMessage(session);

                message.setFrom(new InternetAddress(_config.getValue(com.salesforce.dva.argus.system.SystemConfiguration.Property.ADMIN_EMAIL)));
                message.setSubject(context.getSubject());
                message.setRecipients(Message.RecipientType.TO, getEmailToAddresses(context.getRecipients()));
                message.addHeader("X-Priority", String.valueOf(priority.getXPriority()));

                Multipart multipart = new MimeMultipart();
                BodyPart messageBodyPart1 = new MimeBodyPart();
                messageBodyPart1.setContent(context.getEmailBody(), contentType);
                multipart.addBodyPart(messageBodyPart1);

                context.getImageDetails().ifPresent(imageDetail -> {
                    try {
                        BodyPart imageBodyPart = new MimeBodyPart();
                        DataSource dataSource = new ByteArrayDataSource(imageDetail.getRight(), "image/jpg");
                        imageBodyPart.setDataHandler(new DataHandler(dataSource));
                        imageBodyPart.setHeader("Content-ID", "<" + imageDetail.getLeft() + ">");
                        imageBodyPart.setDisposition(MimeBodyPart.INLINE);
                        multipart.addBodyPart(imageBodyPart);
                    } catch (MessagingException e) {
                        _logger.warn("Unable to embed image into the email with subject" + context.getSubject(), e);
                    }
                });

                message.setContent(multipart);
               
                Transport transport = session.getTransport();
                
                transport.connect(_config.getValue(Property.EMAIL_SMTP_HOST.getName(),Property.EMAIL_SMTP_HOST.getDefaultValue()),
                		_config.getValue(Property.EMAIL_SMTP_USERNAME.getName(),Property.EMAIL_SMTP_USERNAME.getDefaultValue()), 
                		_config.getValue(Property.EMAIL_SMTP_PASSWORD.getName(), Property.EMAIL_SMTP_PASSWORD.getDefaultValue())); 
            	
                transport.sendMessage(message, message.getAllRecipients());
                _logger.info("Sent email having subject '{}' to {}.", context.getSubject(), context.getRecipients());
                return true;
            } catch (Exception ex) {
                String logMessage = MessageFormat.format("MailService: Failed to send an email notification to {0} .", context.getRecipients());
                _logger.error(logMessage, ex);
                throw new SystemException(logMessage, ex);
            }
        } else {
            _logger.warn("Sending email is disabled.  Not sending email having subject '{}' to {}.", context.getSubject(), context.getRecipients());
        }
        
        return false;
    }

    private Address[] getEmailToAddresses(Set<String> recipientEmailAddresses) throws AddressException {
        List<Address> list = new ArrayList<>();

        for (String emailAddress : recipientEmailAddresses) {
            list.add(new InternetAddress(emailAddress));
        }
        return list.toArray(new Address[list.size()]);
    }
    
    @Override
    public Properties getServiceProperties() {
            Properties serviceProps= new Properties();

            for(Property property:Property.values()){
                    serviceProps.put(property.getName(), property.getDefaultValue());
            }
            return serviceProps;
    }

    //~ Enums ****************************************************************************************************************************************

    /**
     * The implementation specific system configuration properties.
     *
     * @author  Tom Valine (tvaline@salesforce.com)
     */
    public enum Property {

        /** The SMTP endpoint URL. */
        EMAIL_SMTP_HOST("service.property.mail.smtp.host", "test.smtp.net"),
        EMAIL_SMTP_AUTH("service.property.mail.smtp.auth", "false"),
        EMAIL_SMTP_STARTTTLS_ENABLED("service.property.mail.smtp.starttls.enable", "false"),
        EMAIL_SMTP_STARTTTLS_REQUIRED("service.property.mail.smtp.starttls.required", "false"),
        EMAIL_SMTP_TRANSPORT_PROTOCOL("service.property.email.transport.protocol","smtps"),
        EMAIL_SMTP_PORT("service.property.smtp.port",""),
        EMAIL_SMTP_USERNAME("service.property.username",""),
        EMAIL_SMTP_PASSWORD("service.property.password","");

        private final String _name;
        private final String _defaultValue;

        private Property(String name, String defaultValue) {
            _name = name;
            _defaultValue = defaultValue;
        }

        /**
         * Returns the property name.
         *
         * @return  The property name.
         */
        public String getName() {
            return _name;
        }

        /**
         * Returns the default property value.
         *
         * @return  The default property value.
         */
        public String getDefaultValue() {
            return _defaultValue;
        }
    }
}
/* Copyright (c) 2016, Salesforce.com, Inc.  All rights reserved. */
