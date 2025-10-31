package fr.tictak.dema.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import fr.tictak.dema.model.MoveRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PdfService {

    private static final Logger logger = LoggerFactory.getLogger(PdfService.class);

    private final TemplateEngine templateEngine;
    private final ResourceLoader resourceLoader;

    /**
     * Generate PDF from MoveRequest data for the specified template
     * @param moveRequest the move request data
     * @param templateName the name of the template to use (e.g., "devis" or "facture")
     * @return byte array of the generated PDF
     * @throws IOException if PDF generation fails
     */
    public byte[] generatePdf(MoveRequest moveRequest, String templateName) throws IOException {
        logger.info("Starting PDF generation for moveId: {}, template: {}", moveRequest.getMoveId(), templateName);

        try {
            // Create Thymeleaf context with move request data
            Context context = new Context(Locale.FRENCH);
            context.setVariable("moveRequest", moveRequest);

            // Process the template to HTML string
            String htmlContent = templateEngine.process(templateName, context);
            logger.debug("HTML template processed successfully for moveId: {}, template: {}", moveRequest.getMoveId(), templateName);

            // Embed images as Base64
            htmlContent = embedImagesAsBase64(htmlContent);

            // Convert HTML to PDF
            byte[] pdfBytes = convertHtmlToPdf(htmlContent);
            logger.info("PDF generated successfully for moveId: {}, template: {}, size: {} bytes",
                    moveRequest.getMoveId(), templateName, pdfBytes.length);

            return pdfBytes;

        } catch (Exception e) {
            logger.error("Failed to generate PDF for moveId: {}, template: {}", moveRequest.getMoveId(), templateName, e);
            throw new IOException("Failed to generate PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Embed images as Base64 data URIs in the HTML content
     * @param htmlContent the HTML content with image references
     * @return HTML content with embedded Base64 images
     * @throws IOException if image loading fails
     */
    private String embedImagesAsBase64(String htmlContent) throws IOException {
        // Map of image paths to embed
        String[][] imageReplacements = {
                {"/images/logotiktak.png", "classpath:static/images/logotiktak.png"},
                {"/images/stamp.png", "classpath:static/images/stamp.png"},
                {"/images/wave-top.png", "classpath:static/images/wave-top.png"},
                {"/images/wave-bottom.png", "classpath:static/images/wave-bottom.png"}
        };

        for (String[] replacement : imageReplacements) {
            String imagePath = replacement[0];
            String resourcePath = replacement[1];

            try {
                Resource imageResource = resourceLoader.getResource(resourcePath);
                if (imageResource.exists()) {
                    String base64Image = loadImageAsBase64(imageResource);
                    String mimeType = getMimeType(imagePath);
                    String dataUri = "data:" + mimeType + ";base64," + base64Image;

                    // Replace both src and background-image references
                    htmlContent = htmlContent.replace("src=\"" + imagePath + "\"", "src=\"" + dataUri + "\"");
                    htmlContent = htmlContent.replace("background-image: url('" + imagePath + "')",
                            "background-image: url('" + dataUri + "')");

                    logger.debug("Embedded image: {}", imagePath);
                } else {
                    logger.warn("Image not found: {}", resourcePath);
                }
            } catch (Exception e) {
                logger.error("Failed to embed image: {}", imagePath, e);
            }
        }

        return htmlContent;
    }

    /**
     * Load an image resource and convert it to Base64 string
     * @param imageResource the image resource to load
     * @return Base64 encoded string of the image
     * @throws IOException if reading fails
     */
    private String loadImageAsBase64(Resource imageResource) throws IOException {
        try (InputStream inputStream = imageResource.getInputStream()) {
            byte[] imageBytes = inputStream.readAllBytes();
            return Base64.getEncoder().encodeToString(imageBytes);
        }
    }

    /**
     * Get MIME type based on file extension
     * @param imagePath the image path
     * @return MIME type string
     */
    private String getMimeType(String imagePath) {
        if (imagePath.endsWith(".png")) {
            return "image/png";
        } else if (imagePath.endsWith(".jpg") || imagePath.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (imagePath.endsWith(".gif")) {
            return "image/gif";
        } else if (imagePath.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png"; // default
    }

    /**
     * Convert HTML string to PDF bytes using OpenHTMLToPDF
     * @param htmlContent the HTML content to convert
     * @return byte array of the PDF
     * @throws IOException if conversion fails
     */
    private byte[] convertHtmlToPdf(String htmlContent) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.withHtmlContent(htmlContent, null);

            // Chargement du font custom en safe lambda
            Resource fontResource = resourceLoader.getResource("classpath:static/fonts/montserrat.ttf");
            if (fontResource.exists()) {
                builder.useFont(() -> {
                    try {
                        return fontResource.getInputStream();
                    } catch (IOException e) {
                        logger.warn("Impossible de charger la police montserrat, police par défaut utilisée", e);
                        return null;
                    }
                }, "montserrat", 400, PdfRendererBuilder.FontStyle.NORMAL, true);
            }

            builder.toStream(outputStream);
            builder.run();

            return outputStream.toByteArray();

        } catch (Exception e) {
            logger.error("Échec de la conversion HTML -> PDF", e);
            throw new IOException("HTML to PDF conversion failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate filename for the PDF based on move request data and document type
     * @param moveRequest the move request
     * @param documentType the type of document ("Devis" or "Facture")
     * @return formatted filename
     */
    public String generateFilename(MoveRequest moveRequest, String documentType) {
        String clientName = "";
        if (moveRequest.getClient() != null) {
            clientName = (moveRequest.getClient().getFirstName() + "_" +
                    moveRequest.getClient().getLastName()).replaceAll("[^a-zA-Z0-9]", "_");
        }

        String moveId = moveRequest.getMoveId();
        return String.format("TicTak_%s_%s_%s.pdf", documentType, moveId, clientName);
    }
}