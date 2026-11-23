package io.github.sye1321.stockbook.identity;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SessionRevocationService {

    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    public SessionRevocationService(FindByIndexNameSessionRepository<? extends Session> sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public void revokeAll(String loginIdentifier) {
        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(loginIdentifier);
        sessions.keySet().forEach(sessionRepository::deleteById);
    }
}
