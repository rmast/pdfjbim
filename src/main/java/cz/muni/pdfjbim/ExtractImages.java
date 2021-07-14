/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//package org.apache.pdfbox.tools;
package cz.muni.pdfjbim;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDAbstractPattern;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.tools.imageio.ImageIOUtil;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * Extracts the images from a PDF file.
 *
 * @author Ben Litchfield
 */
public final class ExtractImages
{
    @SuppressWarnings({"squid:S2068"})
    private static final String PASSWORD = "-password";
    private static final String PREFIX = "-prefix";
    private static final String DIRECTJPEG = "-directJPEG";
    private static final String NOCOLORCONVERT = "-noColorConvert";
    private static final String INCLUDEDENSITY = "-includeDensity";

    private static final List<String> JPEG = Arrays.asList(
            COSName.DCT_DECODE.getName(),
            COSName.DCT_DECODE_ABBREVIATION.getName());

    private boolean useDirectJPEG;
    private boolean includeDensity;
    private boolean noColorConvert;
    private String filePrefix;

    private final Set<COSStream> seen = new HashSet<COSStream>();
    private int imageCounter = 1;

    private ExtractImages()
    {
    }

    /**
     * Entry point for the application.
     *
     * @param args The command-line arguments.
     * @throws IOException if there is an error reading the file or extracting the images.
     */
    public static void main(String[] args) throws IOException
    {
        // suppress the Dock icon on OS X
        System.setProperty("apple.awt.UIElement", "true");

        ExtractImages extractor = new ExtractImages();
        extractor.run(args);
    }

    private void run(String[] args) throws IOException
    {
        if (args.length < 1 || args.length > 4)
        {
            usage();
        }
        else
        {
            String pdfFile = null;
            @SuppressWarnings({"squid:S2068"})
            String password = "";
            for(int i = 0; i < args.length; i++)
            {
                if (args[i].equals(PASSWORD))
                {
                    i++;
                    if (i >= args.length)
                    {
                        usage();
                    }
                    password = args[i];
                }
                else if (args[i].equals(PREFIX))
                {
                    i++;
                    if (i >= args.length)
                    {
                        usage();
                    }
                    filePrefix = args[i];
                }
                else if (args[i].equals(DIRECTJPEG))
                {
                    useDirectJPEG = true;
                }
                else if (args[i].equals(NOCOLORCONVERT))
                {
                    noColorConvert = true;
                }
                else if (args[i].equals(INCLUDEDENSITY))
                {
                    includeDensity = true;
                }
                else
                {
                    if (pdfFile == null)
                    {
                        pdfFile = args[i];
                    }
                }
            }
            if (pdfFile == null)
            {
                usage();
            }
            else
            {
                if (filePrefix == null && pdfFile.length() > 4)
                {
                    filePrefix = pdfFile.substring(0, pdfFile.length() - 4);
                }

                extract(pdfFile, password);
            }
        }
    }

    /**
     * Print the usage requirements and exit.
     */
    private static void usage()
    {
        String message = "Usage: java " + ExtractImages.class.getName() + " [options] <inputfile>\n"
                + "\nOptions:\n"
                + "  -password <password>   : Password to decrypt document\n"
                + "  -prefix <image-prefix> : Image prefix (default to pdf name)\n"
                + "  -directJPEG            : Forces the direct extraction of JPEG/JPX images \n"
                + "                           regardless of colorspace or masking\n"
                + "  -noColorConvert        : Images are extracted with their \n"
                + "                           original colorspace if possible.\n"
                + "  -includeDensity        : Include picture density calculated from scale in PDF.\n"
                + "                           (does not work with -useDirectJPEG or -noColorConvert)\n"
                + "  <inputfile>            : The PDF document to use\n";

        System.err.println(message);
        System.exit(1);
    }

    private void extract(String pdfFile, String password) throws IOException
    {
        PDDocument document = null;
        try
        {
            document = PDDocument.load(new File(pdfFile), password);
            AccessPermission ap = document.getCurrentAccessPermission();
            if (! ap.canExtractContent())
            {
                throw new IOException("You do not have permission to extract images");
            }

            for (PDPage page : document.getPages())
            {
                ImageGraphicsEngine extractor = new ImageGraphicsEngine(page);
                extractor.run();
            }
        }
        finally
        {
            if (document != null)
            {
                document.close();
            }
        }
    }

    private class ImageGraphicsEngine extends PDFGraphicsStreamEngine
    {
        protected ImageGraphicsEngine(PDPage page)
        {
            super(page);
        }

        public void run() throws IOException
        {
            PDPage page = getPage();
            processPage(page);
            PDResources res = page.getResources();
            if (res == null)
            {
                return;
            }
            for (COSName name : res.getExtGStateNames())
            {
                PDExtendedGraphicsState extGState = res.getExtGState(name);
                if (extGState == null)
                {
                    // can happen if key exists but no value
                    continue;
                }
                PDSoftMask softMask = extGState.getSoftMask();
                if (softMask != null)
                {
                    PDTransparencyGroup group = softMask.getGroup();
                    if (group != null)
                    {
                        // PDFBOX-4327: without this line NPEs will occur
                        res.getExtGState(name).copyIntoGraphicsState(getGraphicsState());

                        processSoftMask(group);
                    }
                }
            }
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException
        {
            if (pdImage instanceof PDImageXObject)
            {
                if (pdImage.isStencil())
                {
                    processColor(getGraphicsState().getNonStrokingColor());
                }
                PDImageXObject xobject = (PDImageXObject)pdImage;
                if (seen.contains(xobject.getCOSObject()))
                {
                    // skip duplicate image
                    return;
                }
                seen.add(xobject.getCOSObject());
            }

            // save image
            String name = filePrefix + "-" + imageCounter;
            imageCounter++;

            write2file(pdImage, name, useDirectJPEG, noColorConvert, includeDensity);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3)
                throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        public void clip(int windingRule) throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        public void moveTo(float x, float y) throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        public void lineTo(float x, float y) throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3)
                throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        public Point2D getCurrentPoint() throws IOException
        {
            return new Point2D.Float(0, 0);
        }

        @Override
        public void closePath() throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        public void endPath() throws IOException
        {
            // Empty: add special handling if needed
        }

        @Override
        protected void showGlyph(Matrix textRenderingMatrix,
                                 PDFont font,
                                 int code,
                                 Vector displacement) throws IOException
        {
            RenderingMode renderingMode = getGraphicsState().getTextState().getRenderingMode();
            if (renderingMode.isFill())
            {
                processColor(getGraphicsState().getNonStrokingColor());
            }
            if (renderingMode.isStroke())
            {
                processColor(getGraphicsState().getStrokingColor());
            }
        }

        @Override
        public void strokePath() throws IOException
        {
            processColor(getGraphicsState().getStrokingColor());
        }

        @Override
        public void fillPath(int windingRule) throws IOException
        {
            processColor(getGraphicsState().getNonStrokingColor());
        }

        @Override
        public void fillAndStrokePath(int windingRule) throws IOException
        {
            processColor(getGraphicsState().getNonStrokingColor());
        }

        @Override
        public void shadingFill(COSName shadingName) throws IOException
        {
            // Empty: add special handling if needed
        }

        // find out if it is a tiling pattern, then process that one
        private void processColor(PDColor color) throws IOException
        {
            if (color.getColorSpace() instanceof PDPattern)
            {
                PDPattern pattern = (PDPattern) color.getColorSpace();
                PDAbstractPattern abstractPattern = pattern.getPattern(color);
                if (abstractPattern instanceof PDTilingPattern)
                {
                    processTilingPattern((PDTilingPattern) abstractPattern, null, null);
                }
            }
        }

        /**
         * Writes the image to a file with the filename prefix + an appropriate suffix, like
         * "Image.jpg". The suffix is automatically set depending on the image compression in the
         * PDF.
         *
         * @param pdImage the image.
         * @param prefix the filename prefix.
         * @param directJPEG if true, force saving JPEG/JPX streams as they are in the PDF file.
         * @param noColorConvert if true, images are extracted with their original colorspace if
         * possible.
         * @throws IOException When something is wrong with the corresponding file.
         */
        private void write2file(PDImage pdImage, String prefix, boolean directJPEG,
                                boolean noColorConvert, boolean includeDensity) throws IOException
        {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float imageScale = ctm.getScalingFactorX() + ctm.getScalingFactorY();
            int dpi = (int) ((pdImage.getWidth() + pdImage.getHeight()) * 72 / imageScale);

//          Round calculated dpi to nearest credible scanning resolution
//          This table hasn't been thoroughly tested, only with a big document containing many little 200 dpi pictures.
//          Should it be user configurable, like with a list in a command-line parameter of nearest values to stick to?

//          As the used writeImage method doesn't support separate X and Y dpi fax dpi's like 203x98 aren't included
//          anyway and need a separate construction. The upcoming apache.commons.imaging could provide for
//          a lossless, more speedy alternative for modifying metadata density chunks like EXIF JFIF & PhYS, or would
//          including an external tool like mogrify/identify from ImageMagick be acceptable?

            switch (dpi){
                case 599 : dpi = 600; break;
                case 299 : dpi = 300; break;
                case 199 : dpi = 200; break;
                case 149 : dpi = 150; break;
                case 99  : dpi = 100; break;
            }

            String suffix = pdImage.getSuffix();
            if (suffix == null || "jb2".equals(suffix))
            {
                suffix = "png";
            }
            else if ("jpx".equals(suffix))
            {
                // use jp2 suffix for file because jpx not known by windows
                suffix = "jp2";
            }

            if (hasMasks(pdImage))
            {
                // TIKA-3040, PDFBOX-4771: can't save ARGB as JPEG
                suffix = "png";
            }

            FileOutputStream out = null;
            try
            {
                if (noColorConvert)
                {
                    // We write the raw image if in any way possible.
                    // But we have no alpha information here.
                    BufferedImage image = pdImage.getRawImage();
                    if (image != null)
                    {
                        int elements = image.getRaster().getNumDataElements();
                        suffix = "png";
                        if (elements > 3)
                        {
                            // More then 3 channels: Thats likely CMYK. We use tiff here,
                            // but a TIFF codec must be in the class path for this to work.
                            suffix = "tiff";
                        }
                        out = new FileOutputStream(prefix + "." + suffix);
                        ImageIOUtil.writeImage(image, suffix, out, dpi);
                        out.flush();
                        out.close();
                        return;
                    }
                }

                out = new FileOutputStream(prefix + "." + suffix);
                if ("jpg".equals(suffix))
                {
                    String colorSpaceName = pdImage.getColorSpace().getName();
                    if (!includeDensity && (directJPEG
                            || (PDDeviceGray.INSTANCE.getName().equals(colorSpaceName)
                            || PDDeviceRGB.INSTANCE.getName().equals(colorSpaceName))))
                    {
                        // RGB or Gray colorspace: get and write the unmodified JPEG stream
                        InputStream data = pdImage.createInputStream(JPEG);
                        IOUtils.copy(data, out);
                        IOUtils.closeQuietly(data);
                    }
                    else
                    {
                        // for CMYK and other "unusual" colorspaces, the JPEG will be converted
                        BufferedImage image = pdImage.getImage();
                        if (image != null)
                        {
                            ImageIOUtil.writeImage(image, suffix, out, dpi);
                        }
                    }
                }
                else if ("jp2".equals(suffix))
                {
                    String colorSpaceName = pdImage.getColorSpace().getName();
                    if (!includeDensity && (directJPEG ||
                            (PDDeviceGray.INSTANCE.getName().equals(colorSpaceName) ||
                                    PDDeviceRGB.INSTANCE.getName().equals(colorSpaceName))))
                    {
                        // RGB or Gray colorspace: get and write the unmodified JPEG2000 stream
                        InputStream data = pdImage.createInputStream(
                                Arrays.asList(COSName.JPX_DECODE.getName()));
                        IOUtils.copy(data, out);
                        IOUtils.closeQuietly(data);
                    }
                    else
                    {
                        // for CMYK and other "unusual" colorspaces, the image will be converted
                        BufferedImage image = pdImage.getImage();
                        if (image != null)
                        {
                            ImageIOUtil.writeImage(image, "jpeg2000", out, dpi);
                        }
                    }
                }
                else if ("tiff".equals(suffix) && pdImage.getColorSpace().equals(PDDeviceGray.INSTANCE))
                {
                    BufferedImage image = pdImage.getImage();
                    if (image == null)
                    {
                        return;
                    }
                    // CCITT compressed images can have a different colorspace, but this one is B/W
                    // This is a bitonal image, so copy to TYPE_BYTE_BINARY
                    // so that a G4 compressed TIFF image is created by ImageIOUtil.writeImage()
                    int w = image.getWidth();
                    int h = image.getHeight();
                    BufferedImage bitonalImage = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
                    // copy image the old fashioned way - ColorConvertOp is slower!
                    for (int y = 0; y < h; y++)
                    {
                        for (int x = 0; x < w; x++)
                        {
                            bitonalImage.setRGB(x, y, image.getRGB(x, y));
                        }
                    }
                    ImageIOUtil.writeImage(bitonalImage, suffix, out, dpi);
                }
                else
                {
                    BufferedImage image = pdImage.getImage();
                    if (image != null)
                    {
                        ImageIOUtil.writeImage(image, suffix, out, dpi);
                    }
                }
                out.flush();
            }
            finally
            {
                if (out != null)
                {
                    out.close();
                }
            }
        }

        private boolean hasMasks(PDImage pdImage) throws IOException
        {
            if (pdImage instanceof PDImageXObject)
            {
                PDImageXObject ximg = (PDImageXObject) pdImage;
                return ximg.getMask() != null || ximg.getSoftMask() != null;
            }
            return false;
        }
    }
}
