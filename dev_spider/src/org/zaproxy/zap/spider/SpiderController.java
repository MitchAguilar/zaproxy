/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.spider;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.httpclient.URI;
import org.apache.log4j.Logger;
import org.parosproxy.paros.network.ConnectionParam;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.zap.spider.filters.FetchFilter;
import org.zaproxy.zap.spider.filters.FetchFilter.FetchStatus;
import org.zaproxy.zap.spider.filters.ParseFilter;
import org.zaproxy.zap.spider.parser.SpiderHtmlFormParser;
import org.zaproxy.zap.spider.parser.SpiderHtmlParser;
import org.zaproxy.zap.spider.parser.SpiderParser;
import org.zaproxy.zap.spider.parser.SpiderParserListener;

/**
 * The SpiderController is used to manage the crawling process and interacts directly with the
 * Spider Task threads.
 */
public class SpiderController implements SpiderParserListener {

	/** The fetch filters used by the spider to filter the resources which are fetched. */
	private LinkedList<FetchFilter> fetchFilters;

	/**
	 * The parse filters used by the spider to filter the resources which were fetched, but should
	 * not be parsed.
	 */
	private LinkedList<ParseFilter> parseFilters;

	/** The parsers for HTML files. */
	private List<SpiderParser> htmlParsers;

	/** The spider. */
	private Spider spider;

	/** The connection parameters. */
	private ConnectionParam connectionParam;

	/** The resources visited using GET method. */
	private HashSet<String> visitedGet;

	/** The resources visited using POST method. */
	private HashSet<String> visitedPost;

	/** The Constant log. */
	private static final Logger log = Logger.getLogger(SpiderController.class);

	/**
	 * Instantiates a new spider controller.
	 * 
	 * @param spider the spider
	 * @param connectionParam the connection param
	 */
	protected SpiderController(Spider spider, ConnectionParam connectionParam) {
		super();
		this.spider = spider;
		this.connectionParam = connectionParam;
		this.fetchFilters = new LinkedList<FetchFilter>();
		this.parseFilters = new LinkedList<ParseFilter>();
		this.visitedGet = new HashSet<String>();
		this.visitedPost = new HashSet<String>();

		// Prepare the parsers for HTML
		this.htmlParsers = new LinkedList<SpiderParser>();
		// Simple HTML parser
		SpiderParser parser = new SpiderHtmlParser();
		parser.addSpiderParserListener(this);
		this.htmlParsers.add(parser);

		// HTML Form parser
		parser = new SpiderHtmlFormParser(spider.getSpiderParam());
		parser.addSpiderParserListener(this);
		this.htmlParsers.add(parser);

	}

	/**
	 * Adds a new seed.
	 * 
	 * @param uri the uri
	 */
	protected void addSeed(URI uri) {
		// Create and submit the new task
		SpiderTask task = new SpiderTask(spider, uri, 0, HttpRequestHeader.GET);
		spider.submitTask(task);
		// Add the uri to the found list
		visitedGet.add(uri.toString());
		spider.notifyListenersFoundURI(uri.toString(), FetchStatus.VALID);
	}

	/**
	 * Gets the fetch filters used by the spider during the spidering process.
	 * 
	 * @return the fetch filters
	 */
	protected LinkedList<FetchFilter> getFetchFilters() {
		return fetchFilters;
	}

	/**
	 * Adds a new fetch filter to the spider.
	 * 
	 * @param filter the filter
	 */
	public void addFetchFilter(FetchFilter filter) {
		fetchFilters.add(filter);
	}

	/**
	 * Gets the parses the filters.
	 * 
	 * @return the parses the filters
	 */
	protected LinkedList<ParseFilter> getParseFilters() {
		return parseFilters;
	}

	/**
	 * Adds the parse filter to the spider controller.
	 * 
	 * @param filter the filter
	 */
	public void addParseFilter(ParseFilter filter) {
		parseFilters.add(filter);
	}

	@Override
	public void resourceURIFound(HttpMessage responseMessage, int depth, String uri, boolean shouldIgnore) {
		log.info("New resource found: " + uri);

		// Check if the uri was processed already
		if (visitedGet.contains(uri)) {
			log.debug("URI already visited: " + uri);
			return;
		} else {
			synchronized (visitedGet) {
				visitedGet.add(uri);
			}
		}

		// Create the uriV
		URI uriV = null;
		try {
			uriV = new URI(uri, true);
		} catch (Exception e) {
			log.error("Error while converting to uri: " + uri, e);
		}

		// Check if any of the filters disallows this uri
		for (FetchFilter f : fetchFilters) {
			FetchStatus s = f.checkFilter(uriV);
			if (s != FetchStatus.VALID) {
				log.warn("URI: " + uriV + " was filtered by a filter with reason: " + s);
				spider.notifyListenersFoundURI(uri, s);
				return;
			}
		}

		// Check if should be ignored and not fetched
		if (shouldIgnore) {
			log.warn("URI: " + uriV + " is valid, but will not be fetched, by parser reccommendation.");
			spider.notifyListenersFoundURI(uri, FetchStatus.VALID);
			return;
		}

		spider.notifyListenersFoundURI(uri, FetchStatus.VALID);

		// Submit the task
		SpiderTask task = new SpiderTask(spider, uriV, depth, HttpRequestHeader.GET);
		spider.submitTask(task);
	}

	/* (non-Javadoc)
	 * 
	 * @see
	 * org.zaproxy.zap.spider.parser.SpiderParserListener#resourceFound(org.parosproxy.paros.network
	 * .HttpMessage, java.lang.String) */
	@Override
	public void resourceURIFound(HttpMessage responseMessage, int depth, String uri) {
		resourceURIFound(responseMessage, depth, uri, false);
	}

	/**
	 * Gets the instances for the parsers.
	 * 
	 * @return the parser
	 */
	public List<SpiderParser> getParsers() {
		return htmlParsers;
	}

	@Override
	public void resourcePostURIFound(HttpMessage responseMessage, int depth, String uri, String requestBody) {
		log.info("New POST resource found: " + uri);

		// Check if the uri was processed already
		if (visitedPost.contains(uri)) {
			log.debug("URI already visited: " + uri);
			return;
		} else {
			synchronized (visitedPost) {
				visitedPost.add(uri);
			}
		}

		// Create the uriV
		URI uriV = null;
		try {
			uriV = new URI(uri, true);
		} catch (Exception e) {
			log.error("Error while converting to uri: " + uri, e);
		}

		// Check if any of the filters disallows this uri
		for (FetchFilter f : fetchFilters) {
			FetchStatus s = f.checkFilter(uriV);
			if (s != FetchStatus.VALID) {
				log.warn("URI: " + uriV + " was filtered by a filter with reason: " + s);
				spider.notifyListenersFoundURI(uri, s);
				return;
			}
		}

		spider.notifyListenersFoundURI(uri, FetchStatus.VALID);

		// Submit the task
		SpiderTask task = new SpiderTask(spider, uriV, depth, HttpRequestHeader.POST, requestBody);
		spider.submitTask(task);

	}
}
