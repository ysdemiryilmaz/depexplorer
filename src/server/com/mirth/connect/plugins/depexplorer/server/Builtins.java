package com.mirth.connect.plugins.depexplorer.server;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Names that exist in Mirth's JavaScript / Velocity environment without being defined by the user. */
final class Builtins {

    private Builtins() {}

    /** Functions that can be called without a prefix in Mirth scripts. */
    static final Set<String> FUNCTIONS = new HashSet<String>(Arrays.asList(
            // JavaScript
            "parseInt", "parseFloat", "isNaN", "isFinite", "decodeURI", "decodeURIComponent", "encodeURI", "encodeURIComponent", "escape", "unescape",
            "eval", "String", "Number", "Boolean", "Object", "Array", "Date", "RegExp", "Error", "TypeError", "RangeError", "SyntaxError", "ReferenceError",
            "EvalError", "URIError", "InternalError", "Function", "Symbol", "Map", "Set", "WeakMap", "WeakSet", "Promise", "BigInt", "Proxy", "Reflect",
            "ArrayBuffer", "Uint8Array", "Int8Array", "Uint16Array", "Int16Array", "Uint32Array", "Int32Array", "Float32Array", "Float64Array", "DataView",
            "Iterator", "StopIteration",
            // Rhino / E4X
            "XML", "XMLList", "Namespace", "QName", "isXMLName", "importClass", "importPackage", "JavaImporter", "JavaAdapter", "Packages", "java", "javax",
            "org", "com", "net", "edu", "getClass", "print", "sync", "spawn", "load", "loadClass", "defineClass", "deserialize", "serialize", "gc", "version",
            "uneval", "toSource", "readFile", "readUrl", "runCommand", "quit", "help", "seal", "toint32", "toString", "valueOf", "hasOwnProperty",
            // Mirth (JavaScriptBuilder / userutil)
            "$", "$c", "$co", "$gc", "$g", "$r", "$s", "$cfg", "$secrets", "createSegment", "createSegmentAfter", "getArrayOrXmlLength", "newStringOrUndefined",
            "newBooleanOrUndefined", "newNumberOrUndefined", "validate", "getAttachments", "getAttachmentIds", "getAttachment", "addAttachment",
            "updateAttachment", "getMergedConnectorMessage", "doFilter", "doTransform", "doScript", "doPreprocess", "doPostprocess", "doDeploy",
            "doUndeploy", "doBatch", "filterRule", "transformStep", "getAttachmentsForMessage", "reattachAttachments", "setTimeout", "setInterval",
            "clearTimeout", "clearInterval", "require", "Base64Util", "DateUtil", "FileUtil", "HTTPUtil", "SMTPConnectionFactory", "DatabaseConnectionFactory",
            "XmlUtil", "JsonUtil", "SerializerFactory", "ResponseFactory", "ChannelUtil", "AlertSender", "VMRouter", "Attachment", "AttachmentUtil",
            "ContextFactory", "MessageHeaders", "MessageParameters", "RawMessage", "Response", "Status", "DestinationSet", "UUIDGenerator",
            "NCPDPUtil", "DICOMUtil", "HL7Util", "ACKGenerator", "Lists", "Maps", "MirthCachedRowSet", "MirthJSONUtil"));

    /** Velocity names Mirth puts into the template context. */
    static final Set<String> VELOCITY = new HashSet<String>(Arrays.asList("message", "DATE", "date", "COUNT", "UUID", "SYSTIME", "ORIGINALNAME",
            "originalFilename", "channelName", "channelId", "XmlUtil", "JsonUtil", "maps", "encoder", "connectorMessage", "formatter", "math", "esc",
            "number", "sorter", "list", "alternator", "display", "string", "raw", "attachments"));

    /** Source map / response map keys set by Mirth connectors (so $('originalFilename') is not "unresolved"). */
    static final Set<String> MAP_KEYS = new HashSet<String>(Arrays.asList("originalFilename", "fileDirectory", "fileSize", "fileLastModified",
            "remoteAddress", "remotePort", "localAddress", "localPort", "method", "uri", "url", "contextPath", "headers", "parameters", "query",
            "destinationSet", "mirth_source", "mirth_type", "mirth_version", "mirth_event", "mirth_name", "channelId", "channelName", "messageId",
            "sourceChannelId", "sourceChannelIds", "sourceMessageId", "sourceMessageIds", "sourceMetaDataId", "destinationName", "destinationId",
            "connectorName", "metaDataId", "ackResponse", "ackCode", "ackMessage", "responseStatusLine", "responseHeaders", "responseStatusCode",
            "responseStatus", "jmsMessageId", "jmsCorrelationId", "jmsTimestamp", "jmsType", "jmsDestination", "jmsReplyTo", "jmsDeliveryMode",
            "jmsExpiration", "jmsPriority", "jmsRedelivered", "dicomMessage", "receiverId", "senderId", "pollStart", "pollComplete", "pollSequenceId",
            "subject", "from", "to", "cc", "bcc", "replyTo", "rowNumber", "Postprocessor", "isBatch", "batchComplete", "batchSequenceId", "fileName",
            "filename", "status"));
}
