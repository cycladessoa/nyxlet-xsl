/*******************************************************************************
 * Copyright (c) 2012, THE BOARD OF TRUSTEES OF THE LELAND STANFORD JUNIOR UNIVERSITY
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *    Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *    Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *    Neither the name of the STANFORD UNIVERSITY nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cyclades.nyxlet.xsl.actionhandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.cyclades.annotations.AHandler;
import org.cyclades.engine.MetaTypeEnum;
import org.cyclades.engine.NyxletSession;
import org.cyclades.engine.api.Nyxlet;
import org.cyclades.engine.nyxlet.templates.stroma.STROMANyxlet;
import org.cyclades.engine.nyxlet.templates.stroma.actionhandler.ChainableActionHandler;
import org.cyclades.engine.stroma.STROMAResponse;
import org.cyclades.engine.stroma.STROMAResponseWriter;
import org.cyclades.engine.util.GenericXMLObject;
import org.cyclades.engine.util.LRUCache;
import org.cyclades.engine.validator.NoJSON;
import org.cyclades.engine.validator.ParameterHasValue;
import org.cyclades.io.ResourceRequestUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.XMLFilter;
import org.xml.sax.XMLReader;

@AHandler("xslt")
public class FilterTransform extends ChainableActionHandler {

    public FilterTransform (STROMANyxlet parentNyxlet) {
        super(parentNyxlet);
    }

    @Override
    public void init () throws Exception {
        this.getFieldValidators().add(new NoJSON()).add(new ParameterHasValue(XSL_PARAMETER));
        int maxLRUCacheSize = 10;
        final Properties externalProperties = getParentNyxlet().getExternalProperties();
        if( externalProperties.containsKey(LRU_CACHE_SIZE) ) {
            maxLRUCacheSize = Integer.parseInt(externalProperties.getProperty(LRU_CACHE_SIZE));
        }
        getParentNyxlet().logDebug("LRU Cache Size is: " + maxLRUCacheSize);
        this.xslTemplates = Collections.synchronizedMap(new LRUCache<String, Templates>(maxLRUCacheSize));
        if( externalProperties.containsKey(DOCUMENT_ROOT) ) {
            this.documentRoot = externalProperties.getProperty(DOCUMENT_ROOT);
        }
    } // end of init(...)

    @Override
    public void handleMapChannel (NyxletSession nyxletSession, Map<String, List<String>> baseParameters, STROMAResponseWriter stromaResponseWriter) throws Exception {
        handleLocal(nyxletSession, baseParameters, stromaResponseWriter, (Node)nyxletSession.getMapChannelObject(MAP_CHANNEL_OBJECT));
    }

    @Override
    public void handleSTROMAResponse (final NyxletSession nyxletSession, final Map<String, List<String>> baseParameters,
                                      final STROMAResponseWriter stromaResponseWriter, final STROMAResponse stromaResponse)
        throws Exception
    {
        Node domRoot;
        // Do we have binary input?
        if (nyxletSession.getMapChannel().containsKey(BINARY_MAP_CHANNEL_OBJECT)) {
            domRoot = new GenericXMLObject(new String((byte[])nyxletSession.getMapChannelObject(BINARY_MAP_CHANNEL_OBJECT))).getRootElement();
            nyxletSession.getMapChannel().remove(BINARY_MAP_CHANNEL_OBJECT);

        } else {
            if (stromaResponse != null) {
                domRoot = (Node)stromaResponse.getData();
            } else {
                // XXX - This may have a STROMA wrapper
                domRoot = (Node)nyxletSession.getDataObject();
            }
        }
        handleLocal(nyxletSession, baseParameters, stromaResponseWriter, domRoot);
    } // end of handleSTROMAResponse(...)

    public static void elementToStream(Element element, OutputStream out) throws Exception {
        final DOMSource source = new DOMSource(element);
        final StreamResult result = new StreamResult(out);
        final TransformerFactory transFactory = TransformerFactory.newInstance();
        transFactory.newTransformer().transform(source, result);
    } // end of elementToStream(...)

    private void handleLocal (NyxletSession nyxletSession, Map<String, List<String>> baseParameters, STROMAResponseWriter stromaResponseWriter, Node domRoot) throws Exception {
        final String eLabel = "FilterTransform.handleLocal: ";
        InputStream sourceInputStream = null;
        try {
            final List<String> xslParam = null2emptyList(baseParameters.get(XSL_PARAMETER));
            final SAXTransformerFactory transformerFactory =
                    (SAXTransformerFactory) SAXTransformerFactory.newInstance();
            final XMLReader reader = getXMLReader(xslParam, transformerFactory, baseParameters);
            final Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");

            InputSource transformationSource;
            if (baseParameters.containsKey(SOURCE)) {
                sourceInputStream = ResourceRequestUtils.getInputStream(baseParameters.get(SOURCE).get(0), null);
                transformationSource = new InputSource(sourceInputStream);

            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                if (domRoot instanceof Document) {
                    domRoot = ((Document) domRoot).getDocumentElement();
                }
                Element domElement = (Element) domRoot;
                elementToStream(domElement, baos);
                transformationSource = new InputSource(new ByteArrayInputStream(baos.toByteArray()));
            }

            // We would prefer to have the data transported without serialization...so we'll set the map channel data accordingly here...
            // If we are chaining to another service, simply use the MapChannel, otherwise, write out to the stream. We could write to both
            // mechanisms, but it may be wasteful for large responses...so here's how to control that:
            boolean serialize = baseParameters.containsKey(FORCE_SERIALIZATION) ? baseParameters.get(FORCE_SERIALIZATION).get(0).equalsIgnoreCase("true") : false;
            if (nyxletSession.chainsForward() && !serialize) {
                DOMResult domResult = new DOMResult();
                transformer.transform(new SAXSource(reader, transformationSource), domResult);
                // Utilize the MapChannel
                nyxletSession.putMapChannelObject(MAP_CHANNEL_OBJECT, domResult.getNode());

            } else {
                // We want to remove any "dom" entry from the MapChannel since we consume it and don't want it to go any further, for this implementation.
                nyxletSession.getMapChannel().remove(MAP_CHANNEL_OBJECT);

                transformer.transform(new SAXSource(reader, transformationSource),
                                      new StreamResult(stromaResponseWriter.getOutputStream()));
            }

        } catch (Exception e) {
            getParentNyxlet().logStackTrace(e);
            handleException(nyxletSession, stromaResponseWriter, eLabel, e);

        } finally {
            stromaResponseWriter.done();
            if (sourceInputStream != null) try { sourceInputStream.close(); } catch (Exception e) {}
        }
    } // end of handleLocal(...)

    public boolean isSTROMAResponseCompatible (STROMAResponse response) throws UnsupportedOperationException {
        try {
            if (!MetaTypeEnum.detectMetaTypeEnum(response.getData()).equals(MetaTypeEnum.XML)) return false;
            return true;
        } catch (Exception e) {
            throw new UnsupportedOperationException(e);
        }
    } // end of isSTROMAResponseCompatible(...)

    @Override
    public Object[] getMapChannelKeyTargets (NyxletSession nyxletSession) {
        return new Object[]{MAP_CHANNEL_OBJECT};
    }

    private XMLReader getXMLReader(final List<String> uris, final SAXTransformerFactory transformerFactory, final Map<String, List<String>> params) throws Exception {
        // TODO: Make it use SAXParserProvider
        final SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        final SAXParser saxParser = factory.newSAXParser();
        XMLReader reader = saxParser.getXMLReader();

        for(String uri : uris) {
            Templates tmpl = null;
            if( this.xslTemplates.containsKey(uri) ) {
                tmpl = this.xslTemplates.get(uri);
            } else {
                InputStream is = null;
                final String reqURI = ( this.documentRoot != null ? this.documentRoot+"/" : "" ) + uri;
                try {
                    is = ResourceRequestUtils.
                            getInputStream(Nyxlet.getEngineContext().getCanonicalEngineDirectoryPath(reqURI), null);
                    tmpl = transformerFactory.newTemplates(new StreamSource(is));
                } finally {
                    if( is != null ) { try { is.close(); } catch(Exception ignore) { } }
                }
                this.xslTemplates.put(uri, tmpl);
            }

            final XMLFilter xmlFilter = transformerFactory.newXMLFilter(tmpl);
            setTransformParameters(xmlFilter, params);
            xmlFilter.setParent(reader);
            reader = xmlFilter;
        }
        return reader;
    }

    private static List<String> null2emptyList(final List<String> list) {
        if( list == null ) { return new LinkedList<String>(); }
        return list;
    }

    private void setTransformParameters(final XMLFilter xmlFilter, final Map<String, List<String>> params) {
            if (!params.containsKey(XSL_PARAMETER_NAMES)) {
                    return;
            }
            if (!(xmlFilter instanceof net.sf.saxon.Filter)) {
                    return;
            }
            final Transformer transformer = ((net.sf.saxon.Filter) xmlFilter).getTransformer();
            for (final String names : params.get(XSL_PARAMETER_NAMES)) {
                    for (final String name : names.split(",")) {
                            if (params.containsKey(name)) {
                                    transformer.setParameter(name, params.get(name).get(0));
                            }
                    }
            }
    }

    private Map <String, Templates> xslTemplates;
    private String documentRoot = null;

    public static final String XSL_PARAMETER             = "xsl";
    public static final String XSL_PARAMETER_NAMES       = "xsl-parameter-names";
    public static final String LRU_CACHE_SIZE            = "LRUCacheSize";
    public static final String MAP_CHANNEL_OBJECT        = "dom";
    public static final String BINARY_MAP_CHANNEL_OBJECT = "binary";
    public static final String FORCE_SERIALIZATION       = "serialize";
    public static final String SOURCE                    = "source";
    public static final String DOCUMENT_ROOT             = "documentRoot";
}
