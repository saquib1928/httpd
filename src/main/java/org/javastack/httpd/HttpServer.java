package org.javastack.httpd;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpServer {
	public static final int DEFAULT_READ_TIMEOUT = 60000;

	final ExecutorService pool = Executors.newCachedThreadPool();
	final AtomicBoolean running = new AtomicBoolean(false);
	final int port;
	final File baseDir;

	int readTimeout = DEFAULT_READ_TIMEOUT;

	public static void main(final String[] args) throws Throwable {
		if (args.length < 2) {
			System.out.println(HttpServer.class.getName() + " <port> <directory>");
			return;
		}
		final int port = Integer.parseInt(args[0]);
		final String dir = args[1];
		final HttpServer srv = new HttpServer(port, dir);
		srv.start();
	}

	public HttpServer(final int port, final String baseDir) throws IOException {
		this.port = port;
		this.baseDir = new File(baseDir).getCanonicalFile();
	}

	public void start() {
		running.set(true);
		pool.submit(new Listener(port));
	}

	public void stop() {
		running.set(false);
		shutdownAndAwaitTermination(pool);
	}

	public void setReadTimeoutMillis(final int readTimeout) {
		this.readTimeout = readTimeout;
	}

	boolean shutdownAndAwaitTermination(final ExecutorService pool) {
		pool.shutdown(); // Disable new tasks from being submitted
		try {
			// Wait a while for existing tasks to terminate
			if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
				pool.shutdownNow(); // Cancel currently executing tasks
				// Wait a while for tasks to respond to being cancelled
				if (!pool.awaitTermination(60, TimeUnit.SECONDS))
					return false;
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			pool.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		}
		return true;
	}

	class Listener implements Runnable {
		final int port;

		Listener(final int port) {
			this.port = port;
		}

		@Override
		public void run() {
			ServerSocket server = null;
			try {
				server = new ServerSocket();
				server.setSoTimeout(1000);
				server.setReuseAddress(true);
				server.bind(new InetSocketAddress(port));
				while (running.get()) {
					Socket client = null;
					try {
						client = server.accept();
						client.setSoTimeout(readTimeout);
						pool.submit(new Connection(client));
					} catch (SocketTimeoutException e) {
						continue;
					} catch (Exception e) {
						Closer.close(client);
					}
				}
			} catch (IOException e) {
				e.printStackTrace(System.out);
			} finally {
				Closer.close(server);
			}
		}
	}

	class Connection implements Runnable {
		private static final String HDR_HTTP_VER = "HTTP/1.0";
		private static final String HDR_CACHE_CONTROL = "Cache-Control: private, max-age=0";
		private static final String HDR_CONNECTION_CLOSE = "Connection: close";
		private static final String HDR_SERVER = "Server: httpd";
		private static final String CRLF = "\r\n";

		final Socket client;

		Connection(final Socket client) {
			this.client = client;
		}

		@Override
		public void run() {
			BufferedReader in = null;
			PrintStream out = null;
			FileInputStream fis = null;
			try {
				in = new BufferedReader(new InputStreamReader(client.getInputStream()));
				out = new PrintStream(client.getOutputStream());
				// Read Head (GET / HTTP/1.0)
				final String header = in.readLine();
				final String[] hdrTokens = header.split(" ");
				final String METHOD = hdrTokens[0];
				final String URI = URLDecoder.decode(hdrTokens[1], "ISO-8859-1");
				final String VERSION = hdrTokens[2];
				// Read Headers
				while (!in.readLine().isEmpty()) {
					continue;
				}
				if (!"HTTP/1.0".equals(VERSION) && !"HTTP/1.1".equals(VERSION)) {
					throw HttpError.HTTP_400;
				} else if (!"GET".equals(METHOD)) {
					throw HttpError.HTTP_405;
				} else {
					final File f = new File(baseDir, URI).getCanonicalFile();
					if (!f.getPath().startsWith(baseDir.getPath())) {
						throw HttpError.HTTP_400;
					}
					if (!f.exists() || f.isDirectory()) {
						throw HttpError.HTTP_404;
					}
					out.append(HDR_HTTP_VER).append(" 200 OK").append(CRLF);
					out.append("Content-Length: ").append(String.valueOf(f.length())).append(CRLF);
					out.append(HDR_CACHE_CONTROL).append(CRLF);
					out.append(HDR_CONNECTION_CLOSE).append(CRLF);
					out.append(HDR_SERVER).append(CRLF);
					out.append(CRLF);
					//
					fis = new FileInputStream(f);
					final byte[] buf = new byte[512];
					int len = 0;
					while ((len = fis.read(buf)) != -1) {
						out.write(buf, 0, len);
					}
					out.flush();
				}
			} catch (HttpError e) {
				sendError(out, e, e.getHttpText());
			} catch (SocketTimeoutException e) {
				sendError(out, HttpError.HTTP_408, e.getMessage());
			} catch (IOException e) {
				sendError(out, HttpError.HTTP_500, e.getMessage());
			} finally {
				Closer.close(fis);
				Closer.close(in);
				Closer.close(out);
				Closer.close(client);
			}
		}

		void sendError(final PrintStream out, final HttpError e, final String body) {
			sendError(out, e.getHttpCode(), e.getHttpText(), body);
		}

		void sendError(final PrintStream out, final int code, final String text, final String body) {
			out.append(HDR_HTTP_VER).append(' ').append(String.valueOf(code)).append(' ').append(text)
					.append(CRLF);
			out.append("Content-Length: ").append(String.valueOf(body.length())).append(CRLF);
			out.append("Content-Type: text/plain; charset=ISO-8859-1").append(CRLF);
			out.append(HDR_CACHE_CONTROL).append(CRLF);
			out.append(HDR_CONNECTION_CLOSE).append(CRLF);
			out.append(HDR_SERVER).append(CRLF);
			out.append(CRLF);
			out.append(body);
			out.flush();
		}
	}

	static class HttpError extends Exception {
		public static final HttpError HTTP_400 = new HttpError(400, "Bad Request");
		public static final HttpError HTTP_404 = new HttpError(404, "Not Found");
		public static final HttpError HTTP_405 = new HttpError(405, "Method Not Allowed");
		public static final HttpError HTTP_408 = new HttpError(405, "Request Timeout");
		public static final HttpError HTTP_500 = new HttpError(500, "Internal Server Error");

		private static final long serialVersionUID = 42L;

		private final int code;
		private final String text;

		HttpError(final int code, final String text) {
			this.code = code;
			this.text = text;
		}

		public int getHttpCode() {
			return code;
		}

		public String getHttpText() {
			return text;
		}

		@Override
		public Throwable fillInStackTrace() {
			return this;
		}
	}

	static class Closer {
		static void close(final Closeable c) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ign) {
				}
			}
		}

		static void close(final Socket c) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ign) {
				}
			}
		}

		static void close(final ServerSocket c) {
			if (c != null) {
				try {
					c.close();
				} catch (Exception ign) {
				}
			}
		}
	}
}
