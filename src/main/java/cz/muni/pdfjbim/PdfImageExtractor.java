/*
 *  Copyright 2011 Radim Hatlapatka.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */
package cz.muni.pdfjbim;

import com.itextpdf.text.DocumentException;
import com.itextpdf.text.pdf.PdfReader;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.parser.PdfReaderContentParser;
import cz.muni.pdfjbim.pdf.MyImageRenderListener;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
//import org.apache.pdfbox.pdmodel.graphics.xobject.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * class allowing extraction of images from a PDF document
 * @author Radim Hatlapatka (hata.radim@gmail.com)
 */
public class PdfImageExtractor {

    private int imageCounter = 1;
    private final List<String> namesOfImages = new ArrayList<>();
    private final List<PdfImageInformation> originalImageInformations = new ArrayList<>();
    private static final Logger log = LoggerFactory.getLogger(PdfImageExtractor.class);
    private static final String TMP_DIR = System.getProperty("java.io.tmpdir");

    private final boolean skipJBig2Images = true;
    // TODO: add suitable handling of recompressing JBIG2 images,
    // TODO: currently the global dictionary is not properly replaced in PdfImageReplacer resulting in creating second one
    // TODO: => the resulting PDF size is increased instead of being decreesed => for now setting default as tru => skipping such images

    /**
     * @return names of images in a list
     */
    public List<String> getNamesOfImages() {
        return namesOfImages;
    }

    /**
     *
     * @return list of informations about images
     */
    public List<PdfImageInformation> getOriginalImageInformations() {
        return originalImageInformations;
    }

    /**
     * This method extracts images from PDF
     * @param pdfFile name of input PDF file
     * @param password password for access to PDF if needed
     * @param pagesToProcess list of pages which should be processed if null given => processed all pages
     *      -- not working yet
     * @param binarize -- enables processing of nonbitonal images as well (LZW is still not
     *      processed because of output with inverted colors)
     * @throws PdfRecompressionException if problem to extract images from PDF
     */
    public void extractImages(String pdfFile, String password, Set<Integer> pagesToProcess, Boolean binarize) throws PdfRecompressionException {
        if (binarize == null) {
            binarize = false;
        }
        // checking arguments and setting appropriate variables
        if (pdfFile == null) {
            throw new IllegalArgumentException("pdfFile must be defined");
        }

log.info("extractImages 2");
        String prefix = null;

        // if prefix is not set then prefix set to name of pdf without .pdf
        // if pdfFile has unconsistent name (without suffix .pdf) and name longer than 4 chars then last for chars are removed
        // and this string set as prefix
        if (pdfFile.length() > 4) {
            prefix = pdfFile.substring(0, pdfFile.length() - 4);
        }

        try (InputStream is = new FileInputStream(pdfFile)) {
            extractImagesUsingPdfParser(is, prefix, password, pagesToProcess, binarize);
        } catch (FileNotFoundException ex) {
            throw new PdfRecompressionException("File " + pdfFile + " doesn't exist", ex);
        } catch (IOException ex) {
            throw new PdfRecompressionException("File " + pdfFile + " can't be read", ex);
        }
    }

    /**
     * Parses a PDF and extracts all the images.
     * @param filename 
     * @throws IOException
     * @throws DocumentException  
     */
    public static void extractImages(String filename) throws IOException, DocumentException {
log.info("extractImages 4");
        PdfReader reader = new PdfReader(filename);
        PdfReaderContentParser parser = new PdfReaderContentParser(reader);
        MyImageRenderListener listener = new MyImageRenderListener("Img%s.%s");
        for (int i = 1; i <= reader.getNumberOfPages(); i++) {
            parser.processContent(i, listener);
        }
    }

    /**
     * This method extracts images by going through all COSObjects pointed from xref table
     * @param is input stream containing PDF file
     * @param prefix output basename for images
     * @param password password for access to PDF if needed
     * @param pagesToProcess list of pages which should be processed if null given => processed all pages
     *      -- not working yet
     * @param binarize -- enables processing of nonbitonal images as well (LZW is still not
     *      processed because of output with inverted colors)
     * @throws PdfRecompressionException if problem to extract images from PDF
     */
    public void extractImagesUsingPdfParser(InputStream is, String prefix, String password, Set<Integer> pagesToProcess,
            Boolean binarize) throws PdfRecompressionException {
        // checking arguments and setting appropriate variables
        if (binarize == null) {
            binarize = false;
        }

        log.debug("Extracting images (binarize set to {})", binarize);

        InputStream inputStream;
        if (password != null) {
            try (ByteArrayOutputStream decryptedOutputStream = new ByteArrayOutputStream()) {
                PdfReader reader = new PdfReader(is, password.getBytes(StandardCharsets.UTF_8));
                PdfStamper stamper = new PdfStamper(reader, decryptedOutputStream);
                stamper.close();
                inputStream = new ByteArrayInputStream(decryptedOutputStream.toByteArray());
            } catch (DocumentException ex) {
                throw new PdfRecompressionException(ex);
            } catch (IOException ex) {
                throw new PdfRecompressionException("Reading file caused exception", ex);
            }
        } else {
            inputStream = is;
        }

        PDFParser parser;
        COSDocument doc = null;
        try {
            parser = new PDFParser((RandomAccessRead) inputStream);
            parser.parse();
            doc = parser.getDocument();


            List<COSObject> objs = doc.getObjectsByType(COSName.XOBJECT);
            if (objs != null) {
                for (COSObject obj : objs) {
                    COSBase subtype = obj.getItem(COSName.SUBTYPE);
                    if (subtype.toString().equalsIgnoreCase("COSName{Image}")) {
                        COSBase imageObj = obj.getObject();
                        COSBase cosNameObj = obj.getItem(COSName.NAME);
                        String key;
                        if (cosNameObj != null) {
                            String cosNameKey = cosNameObj.toString();
                            int startOfKey = cosNameKey.indexOf("{") + 1;
                            key = cosNameKey.substring(startOfKey, cosNameKey.length() - 1);
                        } else {
                            key = "im0";
                        }
                        int objectNum = (int) obj.getObjectNumber();
                        int genNum = obj.getGenerationNumber();
                        //PDResources resources = doc..getResources();
                        PDImageXObject image = (PDImageXObject) PDImageXObject.createXObject(imageObj,null);

                        //PDStream pdStr =

//                                InputStream is = image.getCOSObject().getCOSStream(cosNameObj).createRawInputStream();
//                        List<COSName> filters = pdStr.getFilters();

                        log.debug("Detected image with color depth: {} bits", image.getBitsPerComponent());
//                        if (filters == null) {
//                            continue;
//                        }
//                        log.debug("Detected filters: {}", filters);
//
//
//                        if ((image.getBitsPerComponent() > 1) && (!binarize)) {
//                            log.info("It is not a bitonal image => skipping");
//                            continue;
//                        }
//
//                        // at this moment for preventing bad output (bad coloring) from LZWDecode filter
//                        if (filters.contains(COSName.LZW_DECODE)) {
//                            log.info("This is LZWDecoded => skipping");
//                            continue;
//                        }
//
//                        if (filters.contains(COSName.FLATE_DECODE)) {
//                            log.debug("FlateDecoded image detected");
//                        }
//
//                        if (filters.contains(COSName.JBIG2_DECODE)) {
//                            if (skipJBig2Images) {
//                                log.warn("Allready compressed according to JBIG2 standard => skipping");
//                                continue;
//                            } else {
//                                log.debug("JBIG2 image detected");
//                            }
//                        }
//
//                        // detection of unsupported filters by pdfBox library
//                        if (filters.contains(COSName.JPX_DECODE)) {
//                            log.warn("Unsupported filter JPXDecode => skipping");
//                            continue;
//                        }

                        String name = getUniqueFileName(prefix, image.getSuffix());
                        log.info("Writing image: {}", name);
//                        image.write2file(name);
//                        image.writeImage(image,                                 filename,int dpi)

                        PdfImageInformation pdfImageInfo =
                                new PdfImageInformation(key, image.getWidth(), image.getHeight(), objectNum, genNum);
                                    log.info("Image width {}, height {} (regel 404)\n", new Object[] {image.getWidth(), image.getHeight()});
                        originalImageInformations.add(pdfImageInfo);

                        namesOfImages.add(name + "." + image.getSuffix());

                    }
                }
            }
        } catch (IOException ex) {
            Tools.deleteFilesFromList(namesOfImages);
            throw new PdfRecompressionException("Unable to parse PDF document", ex);
        } catch (Exception ex) {
            Tools.deleteFilesFromList(namesOfImages);
        } finally {
            if (doc != null) {
                try {
                    doc.close();
                } catch (IOException ex) {
                    throw new PdfRecompressionException(ex);
                }
            }
        }
    }


    /**
     * get file name that is not used right now
     * @param prefix represents prefix of the name of file
     * @param suffix represents suffix of the name of file
     * @return file name that is not used right now
     */
    public String getUniqueFileName(String prefix, String suffix) {
        String uniqueName = null;
        File f = null;
        while ((f == null) || (f.exists())) {
            uniqueName = prefix + "-" + imageCounter;
            f = new File(uniqueName + "." + suffix);
            imageCounter++;
        }
        return uniqueName;
    }
}
