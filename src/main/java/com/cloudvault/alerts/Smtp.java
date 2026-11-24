package com.cloudvault.alerts;

import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/** A small SMTP client (implicit TLS on 465, STARTTLS on 587, or none for a server on this computer). AUTH LOGIN. */
final class Smtp {
	private Smtp() {}

	private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\r\\n]", " ").trim(); }

	static void send(String host, String portText, String security, String user, String pass, String from, String to, String subject, String body) throws IOException {
		host = clean(host); from = clean(from); to = clean(to);
		if (host.isEmpty() || from.isEmpty() || to.isEmpty()) throw new IOException("E-mail server, from and to addresses are needed");
		String sec = security == null || security.isBlank() ? "starttls" : security.trim().toLowerCase(Locale.ROOT);
		int port = 587;
		try { if (portText != null && !portText.isBlank()) port = Integer.parseInt(portText.trim()); else if (sec.equals("ssl")) port = 465; } catch (NumberFormatException e) { throw new IOException("The e-mail port must be a number"); }
		boolean local = host.equals("localhost") || host.equals("127.0.0.1");
		if (sec.equals("none") && !local) throw new IOException("Unencrypted e-mail is only allowed for a server on this computer");

		Socket s = new Socket();
		s.connect(new InetSocketAddress(host, port), 10_000); s.setSoTimeout(20_000);
		try {
			if (sec.equals("ssl")) s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
			BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
			Writer out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
			expect(in, 220);
			cmd(out, in, "EHLO cloudvault", 250);
			if (sec.equals("starttls")) {
				cmd(out, in, "STARTTLS", 220);
				s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
				in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
				out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
				cmd(out, in, "EHLO cloudvault", 250);
			}
			if (user != null && !user.isBlank()) {
				cmd(out, in, "AUTH LOGIN", 334);
				cmd(out, in, Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)), 334);
				cmd(out, in, Base64.getEncoder().encodeToString((pass == null ? "" : pass).getBytes(StandardCharsets.UTF_8)), 235);
			}
			cmd(out, in, "MAIL FROM:<" + from + ">", 250);
			cmd(out, in, "RCPT TO:<" + to + ">", 250);
			cmd(out, in, "DATA", 354);
			StringBuilder msg = new StringBuilder();
			msg.append("From: ").append(from).append("\r\nTo: ").append(to).append("\r\nSubject: =?UTF-8?B?").append(Base64.getEncoder().encodeToString(clean(subject).getBytes(StandardCharsets.UTF_8))).append("?=\r\n");
			msg.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
			String b64 = Base64.getMimeEncoder(76, "\r\n".getBytes()).encodeToString(body.getBytes(StandardCharsets.UTF_8));
			msg.append(b64).append("\r\n.\r\n");
			out.write(msg.toString()); out.flush();
			expect(in, 250);
			try { cmd(out, in, "QUIT", 221); } catch (IOException ignored) { /* already delivered */ }
		} finally { try { s.close(); } catch (IOException ignored) { /* done */ } }
	}

	private static void cmd(Writer out, BufferedReader in, String line, int want) throws IOException { out.write(line + "\r\n"); out.flush(); expect(in, want); }

	private static void expect(BufferedReader in, int want) throws IOException {
		String l; int code = -1;
		do {
			l = in.readLine();
			if (l == null) throw new IOException("The mail server closed the connection");
			try { code = Integer.parseInt(l.substring(0, 3)); } catch (RuntimeException e) { throw new IOException("Unexpected answer from the mail server"); }
		} while (l.length() > 3 && l.charAt(3) == '-');
		if (code != want) throw new IOException("The mail server said: " + l.replaceAll("[^\\x20-\\x7E]", "?"));
	}
}
