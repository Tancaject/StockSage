package com.stocksage.integration;

import com.stocksage.config.AdminApiInterceptor;
import com.stocksage.config.RequestIdentity;
import com.stocksage.config.SecurityConfig;
import com.stocksage.config.WebConfig;
import com.stocksage.controller.AuthController;
import com.stocksage.exception.GlobalExceptionHandler;
import com.stocksage.model.entity.Conversation;
import com.stocksage.model.entity.InvestmentReportVersion;
import com.stocksage.model.entity.User;
import com.stocksage.model.entity.UserProfile;
import com.stocksage.repository.ConversationRepository;
import com.stocksage.repository.InvestmentReportVersionRepository;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.repository.UserProfileRepository;
import com.stocksage.service.AuthService;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
})
@EnableJpaRepositories(basePackageClasses = {
        UserAccountRepository.class,
        UserProfileRepository.class,
        ConversationRepository.class,
        InvestmentReportVersionRepository.class
})
@EntityScan(basePackageClasses = {
        User.class,
        UserProfile.class,
        Conversation.class,
        InvestmentReportVersion.class
})
@Import({
        SecurityConfig.class,
        AuthController.class,
        AuthService.class,
        RequestIdentity.class,
        GlobalExceptionHandler.class,
        AdminApiInterceptor.class,
        WebConfig.class,
        TestOwnedResourceController.class,
        TestAdminEvalController.class
})
class AuthIntegrationTestApplication {
}
