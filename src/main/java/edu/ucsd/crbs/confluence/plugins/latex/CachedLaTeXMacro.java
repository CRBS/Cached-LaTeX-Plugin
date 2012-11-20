package edu.ucsd.crbs.confluence.plugins.latex;

import java.util.Map;

import com.atlassian.confluence.content.render.xhtml.ConversionContext;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.macro.Macro;
import com.atlassian.confluence.macro.MacroExecutionException;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
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

import javax.imageio.ImageIO;
import javax.swing.JLabel;

import org.apache.commons.codec.binary.Hex;
import org.scilab.forge.jlatexmath.TeXConstants; 
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
	
	private final AttachmentManager attachmentManager;
	private final SettingsManager settingsManager;

    public CachedLaTeXMacro(AttachmentManager attachmentManager, SettingsManager settingsManager)
    {
    	this.attachmentManager = attachmentManager;
    	this.settingsManager = settingsManager;
    }

    // Confluence < 4.0
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public String execute(Map parameters, String body, RenderContext context) throws MacroException
	{
		if (context.getClass().equals(PageContext.class)) {
			try {
				return execute(parameters, body, ((PageContext) context).getEntity());
			} catch (MacroExecutionException e) {
				throw new MacroException(e);
			}
		}
		throw new MacroException("LaTeX macro is only available on Pages.");
	}

	// Confluence 4.0 + 
    @Override
    public String execute(Map<String, String> parameters, String body, ConversionContext context) throws MacroExecutionException
    {
    	return execute(parameters, body, context.getEntity());
    }
    
    /**
     * This method returns XHTML to be displayed on the page that uses this macro.
     */
    private String execute(Map<String, String> parameters, String body, ContentEntityObject page) throws MacroExecutionException
    {
    	body = body.trim();
    	
    	if (body.length() < 1)
    	{
    		return "";
    	}
    	
    	String latexHash = SHA1(body);
    	String attachmentFileName = latexHash + DOT + ATTACHMENT_EXT;
    	
    	Attachment attachment;
    	
    	if (null == (attachment = this.attachmentManager.getAttachment(page, attachmentFileName)))
    	{
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
        	attachment = new Attachment(attachmentFileName, "image/png", output.size(), body);
        	attachment.setContent(page);
        	
        	try {
				this.attachmentManager.saveAttachment(attachment, null, attachmentData);
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
    	}

        return (attachment == null) ? null :
        		"<div class=\"latex_img\"><img src=\"" + this.settingsManager.getGlobalSettings().getBaseUrl() + 
        						attachment.getDownloadPath() + "\" /></div>";
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
