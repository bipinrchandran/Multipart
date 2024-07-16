package org.***.core.converters;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.xml.bind.DatatypeConverter;

import org.apache.commons.fileupload.MultipartStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.lang.NonNull;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import static org.springframework.http.MediaType.MULTIPART_MIXED;


public class MultipartMixedHttpMessageConverter implements HttpMessageConverter<MultiValueMap<String, ?>>
{
	private static final Logger LOG = LoggerFactory.getLogger(MultipartMixedHttpMessageConverter.class);

	public static final String CONTENT_TYPE = "Content-Type";
	public static final String CONTENT_DISPOSITION = "Content-Disposition";
	public static final String BOUNDARY = "boundary";

	public MultipartMixedHttpMessageConverter()
	{
	}

	@Override
	public boolean canRead(final @NonNull Class<?> clazz, final MediaType mediaType)
	{
		return clazz == MultiValueMap.class && null != mediaType
				&& mediaType.getType().equalsIgnoreCase(MULTIPART_MIXED.getType())
				&& mediaType.getSubtype().equalsIgnoreCase(MULTIPART_MIXED.getSubtype());
	}

	@Override
	@Nonnull
	public MultiValueMap<String, InputStream> read(final @NonNull Class<? extends MultiValueMap<String, ?>> clazz,
			final @NonNull HttpInputMessage inputMessage)
			throws IOException, HttpMessageNotReadableException
	{
		final MediaType contentType = inputMessage.getHeaders().getContentType();
		final Charset charset = (contentType != null && contentType.getCharset() != null)
				? contentType.getCharset()
				: StandardCharsets.UTF_8;
		final InputStream inputStream = inputMessage.getBody();
		final String body = StreamUtils.copyToString(inputStream, charset);
		final MultiValueMap<String, InputStream> resultMap = new LinkedMultiValueMap<>();
		if (Objects.nonNull(contentType))
		{
			String boundary = contentType.getParameters().get(BOUNDARY);
			String boundaryWithoutQuotes = boundary.substring(1, boundary.length() - 1);
			String lastTwoChars = boundaryWithoutQuotes.substring(Math.max(boundaryWithoutQuotes.length() - 2, 0));

			final String replacementBoundary = boundaryWithoutQuotes + lastTwoChars;
			body.replaceAll(boundaryWithoutQuotes, replacementBoundary);

			ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(body.getBytes());
			MultipartStream multipartStream = new MultipartStream(byteArrayInputStream, boundaryWithoutQuotes.getBytes(), 4096,
					null);

			boolean nextPart = multipartStream.skipPreamble();

			if (nextPart)
			{
				for (final Iterator<String> iterator = multipartStream.readHeaders().lines().iterator(); iterator.hasNext(); )
				{
					try
					{
						final String readHeaders = multipartStream.readHeaders();
						if (readHeaders.contains("Content-Disposition"))
						{
							int boundaryIndex = readHeaders.indexOf(boundaryWithoutQuotes);
							String resultString = "";

							if (boundaryIndex != -1)
							{
								resultString = readHeaders.substring(boundaryIndex + boundaryWithoutQuotes.length()).trim();
							}
							else
							{
								// LOG error message
								LOG.error("[Integration] Boundary not found. Original String: {}", readHeaders);
							}

							final Map<String, String> headersMap = readHeadersMap(resultString);

							ContentDisposition contentDisposition = ContentDisposition.parse(headersMap.get(CONTENT_DISPOSITION));

							String responseContentType = headersMap.get(CONTENT_TYPE);
							MediaType mediaTypePart = StringUtils.hasLength(responseContentType)
									? MediaType.parseMediaType(responseContentType)
									: null;

							ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
							multipartStream.readBodyData(bodyStream);

							if (MediaType.APPLICATION_OCTET_STREAM.equals(mediaTypePart) && StringUtils.hasLength(
									contentDisposition.getFilename()))
							{
								final ByteResource value = new ByteResource(contentDisposition.getFilename(),
										DatatypeConverter.parseBase64Binary(String.valueOf(bodyStream)));
								resultMap.add(contentDisposition.getFilename(), value.getInputStream());
							}
						}
						else
						{
							LOG.error("[Integration] Header doesn't contain Content-Disposition! {}", readHeaders);
						}
					}
					catch (Exception e)
					{
						break;
					}
				}
			}
		}

		return resultMap;
	}

	private static Map<String, String> readHeadersMap(final String resultString)
	{
		return Arrays.stream(resultString.split("\\n"))
				.filter(line -> line.contains(":"))
				.collect(Collectors.toMap(
						line -> line.substring(0, line.indexOf(':')).trim(),
						line -> line.substring(line.indexOf(':') + 1).trim()
				));
	}

	@Override
	public void write(final @Nonnull MultiValueMap<String, ?> stringMultiValueMap, final MediaType contentType,
			final @Nonnull HttpOutputMessage outputMessage)
			throws IOException, HttpMessageNotWritableException
	{
		//invoke the custom write multipart
	}

	@Override
	public boolean canWrite(final Class<?> clazz, final MediaType mediaType)
	{
		return false;
	}

	@Nonnull
	@Override
	public List<MediaType> getSupportedMediaTypes()
	{
		return List.of();
	}

	static class ByteResource implements Resource
	{

		private final String fileName;
		private final byte[] data;

		public ByteResource(String fileName, byte[] data)
		{
			this.fileName = fileName;
			this.data = data;
		}

		@Override
		public boolean exists()
		{
			return false;
		}

		@Override
		public URL getURL() throws IOException
		{
			return null;
		}

		@Override
		public URI getURI() throws IOException
		{
			return null;
		}

		@Override
		public File getFile() throws IOException
		{
			return null;
		}

		@Override
		public long contentLength() throws IOException
		{
			return data.length;
		}

		@Override
		public long lastModified() throws IOException
		{
			return 0;
		}

		@Override
		public Resource createRelative(String relativePath) throws IOException
		{
			return null;
		}

		@Nonnull
		@Override
		public String getFilename()
		{
			return fileName;
		}

		@Override
		public String getDescription()
		{
			return fileName;
		}

		@Override
		public InputStream getInputStream() throws IOException
		{
			return new ByteArrayInputStream(data);
		}

		@Nonnull
		byte[] getData()
		{
			return data;
		}
	}
}
