package com.bpm.music.services;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

/** Envío de correos con diseño HTML. Se envían en segundo plano. */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;

    public MailService(JavaMailSender sender,
                       @Value("${spring.mail.username:}") String from,
                       @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.sender = sender;
        this.from = from;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public boolean configurado() {
        return from != null && !from.isBlank();
    }

    public String url(String ruta) {
        return baseUrl + ruta;
    }

    @Async
    public void verificacion(String email, String nombre, String token) {
        enviar(email, "Confirma tu cuenta de SpaceMusic",
                "¡Hola " + nombre + "!",
                "Gracias por unirte a SpaceMusic. Confirma tu correo para empezar a escuchar.",
                "Confirmar mi correo", url("/api/auth/verify?token=" + token),
                "Si no creaste esta cuenta, ignora este mensaje.");
    }

    @Async
    public void recuperacion(String email, String nombre, String token) {
        enviar(email, "Recupera tu contraseña de SpaceMusic",
                "Hola " + nombre,
                "Recibimos una solicitud para cambiar tu contraseña. El enlace es válido durante 2 horas.",
                "Crear nueva contraseña", url("/reset-password.html?token=" + token),
                "Si no lo solicitaste, ignora este mensaje: tu contraseña no cambiará.");
    }

    @Async
    public void cambioEmail(String email, String nombre, String token) {
        enviar(email, "Confirma tu nuevo correo en SpaceMusic",
                "Hola " + nombre,
                "Confirma que este es tu nuevo correo para SpaceMusic. El enlace expira en 24 horas.",
                "Confirmar nuevo correo", url("/api/auth/confirm-email?token=" + token),
                "Si no pediste este cambio, ignora este mensaje.");
    }

    @Async
    public void nuevaCuentaPendiente(String adminEmail, String adminNombre, String nombre, String username,
                                     String email, String token) {
        enviar(adminEmail, "Nueva cuenta por aprobar: " + username,
                "Hola " + adminNombre,
                nombre + " (@" + username + ", " + email + ") acaba de crear una cuenta en SpaceMusic y espera tu aprobación.",
                "Revisar y aprobar", url("/aprobar.html?token=" + token),
                "También puedes aprobarla desde Administración → Cuentas nuevas.");
    }

    @Async
    public void cuentaResuelta(String email, String nombre, boolean aprobada) {
        if (aprobada) {
            enviar(email, "Tu cuenta de SpaceMusic está lista", "¡Bienvenido, " + nombre + "!",
                    "Un administrador aprobó tu cuenta. Ya puedes entrar y empezar a escuchar.",
                    "Entrar a SpaceMusic", url("/login.html"), "");
        } else {
            enviar(email, "Sobre tu cuenta de SpaceMusic", "Hola " + nombre,
                    "Tu solicitud de cuenta no fue aprobada. Si crees que es un error, responde a este correo.",
                    "Abrir SpaceMusic", url("/login.html"), "");
        }
    }

    @Async
    public void solicitudResuelta(String email, String nombre, boolean aprobada, String nota) {
        String texto = aprobada
                ? "¡Tu solicitud para ser colaborador fue aprobada! Ya puedes subir canciones, letras y editar datos."
                : "Tu solicitud para ser colaborador no fue aprobada esta vez.";
        if (nota != null && !nota.isBlank()) texto += " Nota del administrador: " + nota;
        enviar(email, aprobada ? "Ya eres colaborador de SpaceMusic" : "Sobre tu solicitud en SpaceMusic",
                "Hola " + nombre, texto, "Abrir SpaceMusic", url("/"), "");
    }

    private void enviar(String para, String asunto, String titulo, String texto,
                        String boton, String enlace, String pie) {
        if (!configurado()) {
            log.warn("Correo no configurado (spring.mail.username). Enlace para {}: {}", para, enlace);
            return;
        }
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(from, "SpaceMusic");
            h.setTo(para);
            h.setSubject(asunto);
            String e = HtmlUtils.htmlEscape(enlace);
            String html = """
                <div style="background:#000;padding:32px 12px;font-family:Segoe UI,Helvetica,Arial,sans-serif">
                  <div style="max-width:480px;margin:0 auto;background:#111114;border:1px solid #26262c;border-radius:20px;padding:32px;color:#f2f2f5">
                    <div style="margin-bottom:24px;font-size:19px;font-weight:800;color:#fff"><span style="display:inline-block;width:30px;height:30px;line-height:30px;text-align:center;background:#000;border:1px solid #333;border-radius:8px;margin-right:8px">&#9835;</span>SpaceMusic</div>
                    <h1 style="font-size:22px;margin:0 0 12px">%s</h1>
                    <p style="font-size:15px;line-height:1.6;color:#b9b9c6;margin:0 0 24px">%s</p>
                    <a href="%s" style="display:inline-block;background:#fff;color:#000;text-decoration:none;font-weight:700;padding:12px 22px;border-radius:999px">%s</a>
                    <p style="font-size:12px;color:#77778a;margin:24px 0 0;word-break:break-all">O copia este enlace: %s</p>
                    <p style="font-size:12px;color:#77778a;margin:12px 0 0">%s</p>
                  </div>
                </div>""".formatted(HtmlUtils.htmlEscape(titulo), HtmlUtils.htmlEscape(texto), e,
                    HtmlUtils.htmlEscape(boton), e, HtmlUtils.htmlEscape(pie));
            h.setText(titulo + "\n\n" + texto + "\n\n" + enlace + "\n\n" + pie, html);
            sender.send(msg);
        } catch (Exception ex) {
            log.warn("No se pudo enviar el correo a {}: {}", para, ex.getMessage());
            log.info("Enlace para {}: {}", para, enlace);
        }
    }
}
