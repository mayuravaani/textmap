package org.wso2.extension.siddhi.map.text.sinkmapper;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import org.apache.log4j.Logger;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.stream.output.sink.SinkListener;
import org.wso2.siddhi.core.stream.output.sink.SinkMapper;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.OptionHolder;
import org.wso2.siddhi.core.util.transport.TemplateBuilder;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Text output mapper implementation. This will convert Siddhi Event to it's string representation.
 */

@Extension(
        name = "text",
        namespace = "sinkMapper",
        description = "This extension is a Text to Event input mapper. Transports that accept text messages can " +
                "utilize this extension to convert the incoming text messages to Siddhi events. Users can use " +
                "a pre-defined text format where event conversion is carried out without any additional " +
                "configurations, or use placeholders to map from a custom text message.",
        parameters = {
                @Parameter(
                        name = "event.grouping.enabled",
                        description = "If this parameter is set to `true`, events are grouped via a delimiter when " +
                                "multiple events are received. It is required to specify a value for the " +
                                "`delimiter` parameter when the value for this parameter is `true`. ",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"
                ),
                @Parameter(
                        name = "delimiter",
                        description = "This parameter specifies how events are separated when a grouped event is " +
                                "received. This must be a whole line and not a single character. ",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "~~~~~~~~~~"
                ),
                @Parameter(
                        name = "new.line.character",
                        description = "This attribute indicates the new line character of the event that is " +
                                "expected to be received. This is used mostly when communication between 2 types of " +
                                "operating systems is expected. For example, Linux uses '\n' whereas Windows " +
                                "uses '\r\n'as the end of line character. ",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "\n"
                )
        },
        examples = {
                @Example(
                        syntax = "@sink(type='inMemory', topic='stock', @map(type='text'))\n"
                                + "define stream FooStream (symbol string, price float, volume long);\n",
                        description = "This query performs a default text input mapping. The expected output is " +
                                "as follows:"

                                + "symbol:\"WSO2\",\n"
                                + "price:55.6,\n"
                                + "volume:100"

                                + "or"

                                + "symbol:'WSO2',\n"
                                + "price:55.6,\n"
                                + "volume:100"

                                + "If event grouping is enabled, then the output is as follows:"

                                + "symbol:'WSO2',\n"
                                + "price:55.6,\n"
                                + "volume:100\n"
                                + "~~~~~~~~~~\n"
                                + "symbol:'WSO2',\n"
                                + "price:55.6,\n"
                                + "volume:100"
                ),
                @Example(
                        syntax = "@sink(type='inMemory', topic='stock', @map(type='text', " +
                                " @payload(" +
                                "SensorID : {{{symbol}}}/{{{Volume}}},\n" +
                                "SensorPrice : Rs{{{price}}}/=,\n" +
                                "Value : {{{Volume}}}ml”)))",
                        description = "This query performs a custom text mapping. The output is as follows:"

                                + "SensorID : wso2/100,\n"
                                + "SensorPrice : Rs1000/=,\n"
                                + "Value : 100ml"

                                + "for the following siddhi event."

                                + "{wso2,1000,100}"
                )
        }
)

public class TextSinkMapper extends SinkMapper {

    private static final Logger log = Logger.getLogger(TextSinkMapper.class);
    private static final String EVENT_ATTRIBUTE_SEPARATOR = ",";
    private static final String STRING_ENCLOSING_ELEMENT = "\"";
    private static final String OPTION_GROUP_EVENTS = "event.grouping.enabled";
    private static final String OPTION_GROUP_EVENTS_DELIMITER = "delimiter";
    private static final String DEFAULT_EVENTS_DELIMITER = "~~~~~~~~~~";
    private static final String DEFAULT_GROUP_EVENTS = "false";
    private static final String OPTION_NEW_LINE = "new.line.character";
    private static final String DEFAULT_NEW_LINE = "\n";
    private boolean eventGroupEnabled;
    private String eventDelimiter;
    private List<Attribute> attributeList;
    private String endOfLine;
    private String streamID;
    private Map<String, Object> scopes;
    private Mustache mustache;

    @Override
    public String[] getSupportedDynamicOptions() {

        return new String[0];
    }

    @Override
    public void init(StreamDefinition streamDefinition, OptionHolder optionHolder, Map<String, TemplateBuilder>
            payloadTemplateBuilderMap, ConfigReader configReader, SiddhiAppContext siddhiAppContext) {

        MustacheFactory mf = new DefaultMustacheFactory();
        scopes = new HashMap<String, Object>();
        this.streamID = streamDefinition.getId();
        this.attributeList = streamDefinition.getAttributeList();
        this.eventGroupEnabled = Boolean.valueOf(optionHolder.validateAndGetStaticValue(OPTION_GROUP_EVENTS,
                DEFAULT_GROUP_EVENTS));

        this.endOfLine = optionHolder.validateAndGetStaticValue(OPTION_NEW_LINE, DEFAULT_NEW_LINE);
        this.eventDelimiter = optionHolder.validateAndGetStaticValue(OPTION_GROUP_EVENTS_DELIMITER,
                DEFAULT_EVENTS_DELIMITER) + endOfLine;

        //if @payload() is added there must be at least 1 element in it, otherwise a SiddhiParserException raised
        if (payloadTemplateBuilderMap != null && payloadTemplateBuilderMap.size() != 1) {
            throw new SiddhiAppCreationException("Text sink-mapper does not support multiple @payload mappings, " +
                    "error at mapper of '" + streamID + "'");
        }
        if (payloadTemplateBuilderMap != null && payloadTemplateBuilderMap.get(
                payloadTemplateBuilderMap.keySet().iterator().next()).isObjectMessage()) {
            throw new SiddhiAppCreationException("Text sink-mapper does not support object @payload mappings, " +
                    "error at the mapper of '" + streamID + "'");
        }

        if (payloadTemplateBuilderMap != null) {
            String customTemplate = createCustomTemplate(getTemplateFromPayload(streamDefinition), eventGroupEnabled);
            mustache = mf.compile(new StringReader(customTemplate), "customEvent");
        } else {
            String defaultTemplate = createDefaultTemplate(attributeList, eventGroupEnabled);
            mustache = mf.compile(new StringReader(defaultTemplate), "defaultEvent");
        }
    }

    @Override
    public Class[] getOutputEventClasses() {

        return new Class[]{String.class};
    }

    @Override
    public void mapAndSend(Event[] events, OptionHolder optionHolder,
                           Map<String, TemplateBuilder> payloadTemplateBuilderMap, SinkListener sinkListener) {

        if (!eventGroupEnabled) { //Event not grouping
            if (payloadTemplateBuilderMap != null) { //custom mapping case
                for (Event event : events) {
                    if (event != null) {
                        sinkListener.publish(constructCustomMapping(event));
                    }
                }
            } else { //default mapping case
                for (Event event : events) {
                    if (event != null) {
                        sinkListener.publish(constructDefaultMapping(event));
                    }
                }
            }
        } else { //event group scenario
            StringBuilder eventData = new StringBuilder();
            if (payloadTemplateBuilderMap != null) { //custom mapping case
                for (Event event : events) {
                    if (event != null) {
                        eventData.append(constructCustomMapping(event));
                    }
                }
            } else { //default mapping case
                for (Event event : events) {
                    if (event != null) {
                        eventData.append(constructDefaultMapping(event));
                    }
                }
            }
            int idx = eventData.lastIndexOf(eventDelimiter);
            eventData.delete(idx - endOfLine.length(), idx + eventDelimiter.length());
            sinkListener.publish(eventData.toString());
        }
    }

    @Override
    public void mapAndSend(Event event, OptionHolder optionHolder, Map<String, TemplateBuilder>
            payloadTemplateBuilderMap, SinkListener sinkListener) {

        if (!eventGroupEnabled) {
            if (payloadTemplateBuilderMap != null) { //custom mapping case
                if (event != null) {
                    sinkListener.publish(constructCustomMapping(event));
                }
            } else { //default mapping case
                if (event != null) {
                    sinkListener.publish(constructDefaultMapping(event));
                }
            }
        } else {
            StringBuilder eventData = new StringBuilder();
            if (payloadTemplateBuilderMap != null) { //custom mapping case
                if (event != null) {
                    eventData.append(constructDefaultMapping(event));
                }
            } else { //default mapping case
                if (event != null) {
                    eventData.append(constructDefaultMapping(event));
                }
            }
            int idx = eventData.lastIndexOf(eventDelimiter);
            eventData.delete(idx - endOfLine.length(), idx + eventDelimiter.length());
            sinkListener.publish(eventData.toString());
        }
    }

    private Object constructDefaultMapping(Event event) {

        Writer writer = new StringWriter();
        Object[] data = event.getData();
        for (int i = 0; i < data.length; i++) {
            Object attributeValue = data[i];
            Attribute attribute = attributeList.get(i);
            scopes.put(attribute.getName(), attributeValue);
        }
        mustache.execute(writer, scopes);
        return writer.toString();
    }

    private Object constructCustomMapping(Event event) {

        Writer writer = new StringWriter();
        Object[] data = event.getData();
        for (int i = 0; i < data.length; i++) {
            Object attributeValue = data[i];
            Attribute attribute = attributeList.get(i);
            scopes.put(attribute.getName(), attributeValue);
        }
        mustache.execute(writer, scopes);
        return writer.toString();
    }

    private String getTemplateFromPayload(StreamDefinition streamDefinition) {

        List<Element> elements = null;
        for (Annotation sinkAnnotation : streamDefinition.getAnnotations()) {
            Annotation mapAnnotation = AnnotationHelper.getAnnotation(SiddhiConstants.ANNOTATION_MAP,
                    sinkAnnotation.getAnnotations());
            List<Annotation> attributeAnnotations = mapAnnotation.getAnnotations(SiddhiConstants.ANNOTATION_PAYLOAD);
            if (attributeAnnotations.size() == 1) {
                elements = attributeAnnotations.get(0).getElements();
            }
        }
        if (elements != null) {
            return elements.get(0).toString().substring(1, elements.get(0).toString().length() - 1);
        } else {
            throw new SiddhiAppCreationException("There is no template given in the @payload in" + streamID);
        }
    }

    //creating the template based on the attributes given by the user
    private String createDefaultTemplate(List<Attribute> attributeList, boolean isEventGroup) {

        StringBuilder template = new StringBuilder();
        for (Attribute attribute : attributeList) {
            String attributeName = attribute.getName();
            if (attribute.getType().equals(Attribute.Type.STRING)) {
                template.append(attributeName).append(":").append(STRING_ENCLOSING_ELEMENT).append("{{{")
                        .append(attributeName).append("}}}").append(STRING_ENCLOSING_ELEMENT)
                        .append(EVENT_ATTRIBUTE_SEPARATOR).append(endOfLine);
            } else {
                template.append(attributeName).append(":{{{").append(attributeName).append("}}}")
                        .append(EVENT_ATTRIBUTE_SEPARATOR).append(endOfLine);
            }
        }
        int idx = template.lastIndexOf(EVENT_ATTRIBUTE_SEPARATOR);
        if (!isEventGroup) {//template for event not grouping
            template.delete(idx, idx + (EVENT_ATTRIBUTE_SEPARATOR + endOfLine).length());
        } else {//template for event grouping
            template.delete(idx, idx + EVENT_ATTRIBUTE_SEPARATOR.length()).append(eventDelimiter);
        }
        return template.toString();
    }

    //creating the template based on the payload
    private String createCustomTemplate(String customTemplate, boolean isEventGroup) {

        StringBuilder template = new StringBuilder();
        if (!isEventGroup) {//template for event not grouping
            template.append(customTemplate);
        } else {//template for event grouping
            template.append(customTemplate).append(endOfLine).append(eventDelimiter);
        }
        return template.toString();
    }
}
