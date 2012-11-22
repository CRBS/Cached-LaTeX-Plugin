package edu.ucsd.crbs.confluence.plugins.latex;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.content.render.xhtml.ConversionContextOutputType;
import com.atlassian.confluence.content.render.xhtml.DefaultConversionContext;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.Comment;
import com.atlassian.confluence.pages.Draft;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.renderer.PageContext;
import com.atlassian.confluence.setup.settings.SettingsManager;
import com.atlassian.renderer.RenderContext;
import com.atlassian.renderer.v2.RenderMode;
import com.atlassian.renderer.v2.macro.BaseMacro;
import com.atlassian.renderer.v2.macro.MacroException;

import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.StringBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JLabel;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.StringUtils;

import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CachedLaTeXMacro extends BaseMacro implements Macro
{

	@Override
	public boolean hasBody()
	{
		return true;
	}

	@Override
	public RenderMode getBodyRenderMode()
	{
		return RenderMode.NO_RENDER;
	}

	private static final String DOT = ".";
	private static final String ATTACHMENT_EXT = "png";
	private static final int ATTACHMENT_COMMENT_MAX_LENGTH = 254;
	private static final String ATTACHMENT_COMMENT_SUFFIX = "...";
	private static final String ATTACHMENT_MIMETYPE = "image/png";

	private final AttachmentManager attachmentManager;
	private final SettingsManager settingsManager;
	private final PageManager pageManager;

	private static final Logger log = LoggerFactory.getLogger(CachedLaTeXMacro.class);

	public CachedLaTeXMacro(AttachmentManager attachmentManager, SettingsManager settingsManager, PageManager pageManager)
	{
		this.attachmentManager = attachmentManager;
		this.settingsManager = settingsManager;
		this.pageManager = pageManager;
	}

	// Confluence < 4.0
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public String execute(Map parameters, String body, RenderContext renderContext) throws MacroException
	{
		try
		{
			return execute(parameters, body, new DefaultConversionContext(renderContext));
		}
		catch (MacroExecutionException e)
		{
			throw new MacroException(e);
		}
	}

	/**
	 * Process a request to render a {latex} macro
	 */
	@Override
	public String execute(Map<String, String> parameters, String body, ConversionContext conversionContext) throws MacroExecutionException
	{
		String pageTitle = parameters.get("page");
		ContentEntityObject contentObject;
		PageContext pageContext;

		if (StringUtils.isNotBlank(pageTitle))
		{
			contentObject = getPage(conversionContext.getPageContext(), pageTitle);
			pageContext = new PageContext(contentObject);
			if (contentObject == null)
			{
				return null;
			}
		}
		else
		{
			// retrieve a reference to the body object this macro is in
			contentObject = conversionContext.getEntity();
			pageContext = conversionContext.getPageContext();
		}
		String sortBy = null;
		String sortOrder = null;

		// CONF-9989: if this macro is run from within a comment, use the underlying page to find the attachments
		if (contentObject instanceof Comment)
			contentObject = ((Comment) contentObject).getOwner();

		boolean isPreview = (contentObject.getClass() == Draft.class);
		if (isPreview)
		{
			log.debug("IS PREVIEW");
		}

		body = body.trim();
		if (body.length() < 1)
		{
			return "";
		}

		String latexHash = SHA1(body);
		String attachmentFileName = latexHash + DOT + ATTACHMENT_EXT;

		log.debug("{} - Attachment Filename: {}", contentObject.toString(), attachmentFileName);

		Attachment attachment = attachmentManager.getAttachment(contentObject, attachmentFileName);
		String attachmentURL = null;

		if (attachment == null)
		{
			StringBuffer logString = new StringBuffer("Attachment was NULL, need to create new.\nCurrent Attachments:\n");
			List<Attachment> attachments = attachmentManager.getLatestVersionsOfAttachments(contentObject);

			for (Attachment att : attachments)
			{
				logString.append(" - ");
				logString.append(att.toString());
				logString.append("\n");
			}

			log.debug(logString.toString());

			// need to generate image
			TeXFormula formula = new TeXFormula(body);
			TeXIcon icon = formula.createTeXIcon(TeXConstants.STYLE_DISPLAY, 20);

			BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);

			Graphics2D g2 = image.createGraphics();

			JLabel jl = new JLabel();
			jl.setForeground(new Color(0, 0, 0));
			icon.paintIcon(jl, g2, 0, 0);

			final ByteArrayOutputStream output = new ByteArrayOutputStream() {
				@Override
				public synchronized byte[] toByteArray() {
					return this.buf;
				}
			};

			try {
				ImageIO.write(image, "png", output);
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}

			byte[] attachmentDataByteArray = output.toByteArray();

			// If we're previewing, then we don't want to create the attachment yet, we just want to
			// create a base64 URL to show the preview.
			if (isPreview)
			{
				attachmentURL = getBase64StringOfPNGData(attachmentDataByteArray);
			}
			// otherwise, we want to save the attachment to the page for caching
			else
			{
				InputStream attachmentData = new ByteArrayInputStream(attachmentDataByteArray, 0, output.size());

				String attachmentComment = body;
				if (attachmentComment.length() > ATTACHMENT_COMMENT_MAX_LENGTH) {
					attachmentComment = attachmentComment.substring(0, ATTACHMENT_COMMENT_MAX_LENGTH - ATTACHMENT_COMMENT_SUFFIX.length()) + ATTACHMENT_COMMENT_SUFFIX;
				}

				attachment = new Attachment(attachmentFileName, ATTACHMENT_MIMETYPE, output.size(), attachmentComment);
				attachment.setContent(contentObject);

				try {
					attachmentManager.saveAttachment(attachment, null, attachmentData);
				} catch (IOException e) {
					e.printStackTrace();
					return null;
				}
			}
		}
		else
		{
			log.debug("Attachment was NOT NULL: {}", attachment.toString());
		}

		if (attachmentURL == null)
		{
			attachmentURL = settingsManager.getGlobalSettings().getBaseUrl() + attachment.getDownloadPath();
		}

		log.debug("Attachment URL: {}", attachmentURL);

		return (attachmentURL == null) ? null : "<div class=\"latex_img\"><img src=\"" + attachmentURL + "\" /></div>";
	}

	private ContentEntityObject getPage(PageContext context, String pageTitleToRetrieve)
	{
		if (StringUtils.isBlank(pageTitleToRetrieve))
			return context.getEntity();

		String spaceKey = context.getSpaceKey();
		String pageTitle = pageTitleToRetrieve;

		int colonIndex = pageTitleToRetrieve.indexOf(":");
		if (colonIndex != -1 && colonIndex != pageTitleToRetrieve.length() - 1)
		{
			spaceKey = pageTitleToRetrieve.substring(0, colonIndex);
			pageTitle = pageTitleToRetrieve.substring(colonIndex + 1);
		}

		return pageManager.getPage(spaceKey, pageTitle);
	}

	@Override
	public BodyType getBodyType()
	{
		return BodyType.PLAIN_TEXT;
	}

	@Override
	public OutputType getOutputType()
	{
		return OutputType.BLOCK;
	}

	private static String SHA1(String input)
	{
		String result = null;
		MessageDigest crypt;
		try {
			crypt = MessageDigest.getInstance("SHA-1");
			crypt.reset();
			crypt.update(input.getBytes("utf8"));

			result = new String(Hex.encodeHex(crypt.digest()));
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		return result;
	}

	private static String getBase64StringOfPNGData(byte[] pngData)
	{
		StringBuffer output = new StringBuffer("data:image/png;base64,");
		output.append(Base64.encodeBase64String(pngData).replaceAll("(\\r|\\n)", ""));
		return output.toString();
	}
}
