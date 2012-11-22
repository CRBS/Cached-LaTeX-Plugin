package edu.ucsd.crbs.confluence.plugins.latex;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.content.render.xhtml.DefaultConversionContext;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.Comment;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.JLabel;

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
	// @SuppressWarnings({ "unchecked", "rawtypes" })
	// @Override
	// public String execute(Map parameters, String body, RenderContext context) throws MacroException
	// {
	// 	if (context.getClass().equals(PageContext.class)) {
	// 		try {
	// 			return execute(parameters, body, ((PageContext) context).getEntity());
	// 		} catch (MacroExecutionException e) {
	// 			throw new MacroException(e);
	// 		}
	// 	}
	// 	throw new MacroException("LaTeX macro is only available on Pages.");
	// }

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

	// Confluence 4.0 +
	// @Override
	// public String execute(Map<String, String> parameters, String body, ConversionContext context) throws MacroExecutionException
	// {
	// 	return execute(parameters, body, context.getEntity());
	// }

	/**
	 * Process a request to render an {attachments} macro
	 */
	@Override
	public String execute(Map<String, String> parameters, String body, ConversionContext conversionContext) throws MacroExecutionException
	{
		String pageTitle = parameters.get("page");
		ContentEntityObject contentObject;
		PageContext pageContext;

		log.debug("Page Title: {}", pageTitle);

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

	// /**
	//  * This method returns XHTML to be displayed on the page that uses this macro.
	//  */
	// private String execute(Map<String, String> parameters, String body, ContentEntityObject page) throws MacroExecutionException
	// {
		body = body.trim();

		if (body.length() < 1)
		{
			return "";
		}

		String latexHash = SHA1(body);
		String attachmentFileName = latexHash + DOT + ATTACHMENT_EXT;

		log.debug("Attachment Filename: {}", attachmentFileName);

		Attachment attachment = attachmentManager.getAttachment(contentObject, attachmentFileName);

		if (attachment == null)
		{
			log.debug("Attachment was null, need to create new. contentObject: {}", contentObject.toString());

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

			InputStream attachmentData = new ByteArrayInputStream(output.toByteArray(), 0, output.size());

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
		else
		{
			log.debug("Attachment was not null: {}", attachment.toString());
		}

		String url = settingsManager.getGlobalSettings().getBaseUrl() + attachment.getDownloadPath();

		log.debug("Attachment URL: {}", url);

		return (attachment == null) ? null : "<div class=\"latex_img\"><img src=\"" + url + "\" /></div>";
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
}
