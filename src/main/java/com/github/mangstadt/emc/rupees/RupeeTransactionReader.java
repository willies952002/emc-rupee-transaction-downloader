package com.github.mangstadt.emc.rupees;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.Closeable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jsoup.nodes.Document;

import com.github.mangstadt.emc.net.EmcWebsiteConnection;
import com.github.mangstadt.emc.net.EmcWebsiteConnectionImpl;
import com.github.mangstadt.emc.net.InvalidSessionException;
import com.github.mangstadt.emc.rupees.dto.RupeeTransaction;
import com.github.mangstadt.emc.rupees.dto.RupeeTransactionPage;

/**
 * Downloads rupee transactions from the EMC website. Use its {@link Builder}
 * class to create new instances.
 * @author Michael Angstadt
 * @see <a
 * href="http://www.empireminecraft.com/rupees/transactions">http://www.empireminecraft.com/rupees/transactions</a>
 */
public class RupeeTransactionReader implements Closeable {
	private static final Logger logger = Logger.getLogger(RupeeTransactionReader.class.getName());

	private Iterator<RupeeTransaction> transactionsOnCurrentPage;
	private RupeeTransactionPage currentPage;
	private final BlockingQueue<RupeeTransactionPage> queue = new LinkedBlockingQueue<RupeeTransactionPage>();
	private final RupeeTransactionPage noMoreElements = new RupeeTransactionPage(null, null, null, Collections.<RupeeTransaction> emptyList());
	private final Map<Integer, RupeeTransactionPage> buffer = new HashMap<Integer, RupeeTransactionPage>();
	private final Set<Integer> hashesOfReturnedTransactions = new HashSet<Integer>();

	private final PageSource pageSource;
	private final Integer startAtPage, stopAtPage;
	private final Date startAtDate, stopAtDate;
	private final int threads;

	private final Date latestTransactionDate;
	private final AtomicInteger pageCounter;

	private int nextPageToPutInQueue;
	private IOException thrown = null;

	private int deadThreads = 0;
	private boolean cancel = false, endOfStream = false;

	private RupeeTransactionReader(Builder builder) throws IOException {
		pageSource = builder.pageSource;
		threads = builder.threads;
		stopAtPage = builder.stopPage;
		stopAtDate = builder.stopDate;

		EmcWebsiteConnection connection = pageSource.createSession();
		RupeeTransactionPage firstPage = pageSource.getPage(1, connection);

		//get the date of the latest transaction so we know when we've reached the last transaction page
		//(the first page is returned when you request a non-existent page number)
		latestTransactionDate = firstPage.getFirstTransactionDate();

		startAtDate = builder.startDate;
		if (startAtDate == null) {
			startAtPage = builder.startPage;
		} else {
			if (startAtDate.after(firstPage.getLastTransactionDate())) {
				startAtPage = 1;
			} else {
				startAtPage = findStartPage(startAtDate, firstPage.getTotalPages(), connection);
			}
		}

		//start the page download threads
		pageCounter = new AtomicInteger(startAtPage);
		nextPageToPutInQueue = startAtPage;
		for (int i = 0; i < threads; i++) {
			ScrapeThread thread = new ScrapeThread(connection);
			thread.setDaemon(true);
			thread.setName(getClass().getSimpleName() + "-" + i);
			thread.start();
			if (i < threads - 1) {
				connection = pageSource.recreateConnection(connection);
			}
		}
	}

	/**
	 * Uses binary search to find the rupee transaction page that contains a
	 * transactions with the given date (or, as close as it can get to the given
	 * date without exceeding it).
	 * @param startDate the start date
	 * @param totalPages the total number of rupee transaction pages
	 * @param connection the website connection
	 * @return the page number
	 * @throws IOException
	 */
	private int findStartPage(Date startDate, int totalPages, EmcWebsiteConnection connection) throws IOException {
		int curPage = totalPages / 2;
		int nextAmount = curPage / 2;
		while (nextAmount > 0) {
			int amount = nextAmount;
			nextAmount /= 2;

			RupeeTransactionPage page = pageSource.getPage(curPage, connection);
			if (startDate.after(page.getFirstTransactionDate())) {
				curPage -= amount;
				continue;
			}

			if (startDate.before(page.getLastTransactionDate())) {
				curPage += amount;
				continue;
			}

			break;
		}

		return curPage;
	}

	/**
	 * Gets the next rupee transaction. Transactions are returned in descending
	 * order.
	 * @return the next transaction or null if there are no more transactions
	 * @throws IOException
	 */
	public RupeeTransaction next() throws IOException {
		if (endOfStream) {
			return null;
		}

		RupeeTransaction transaction;
		while (true) {
			/*
			 * Check to see if we are done processing the transactions of the
			 * current page. If so, pop the next transaction page from the
			 * queue.
			 */
			while (transactionsOnCurrentPage == null || !transactionsOnCurrentPage.hasNext()) {
				try {
					currentPage = queue.take();
				} catch (InterruptedException e) {
					close();
					endOfStream = true;
					synchronized (this) {
						if (thrown != null) {
							throw thrown;
						}
					}
					return null;
				}

				if (currentPage == noMoreElements) {
					endOfStream = true;
					synchronized (this) {
						if (thrown != null) {
							throw thrown;
						}
					}
					return null;
				}

				transactionsOnCurrentPage = currentPage.getTransactions().iterator();
			}

			transaction = transactionsOnCurrentPage.next();

			/*
			 * If a start date was specified, then skip any transactions that
			 * come after the start date. This is to account for the case when a
			 * transaction page contains transactions that come after the start
			 * date. For example, the transaction with the start date might be
			 * in the middle of the page, so we want to ignore all transactions
			 * that come *before* it on the page (since transactions are listed
			 * in descending order).
			 */
			if (startAtDate != null && transaction.getTs().after(startAtDate)) {
				continue;
			}

			/*
			 * If a stop date was specified, and the transaction's date is the
			 * same as, or comes before, the stop date, then we're reached the
			 * "end of stream". The download threads will terminate in time.
			 */
			if (stopAtDate != null && transaction.getTs().getTime() <= stopAtDate.getTime()) {
				endOfStream = true;
				return null;
			}

			/*
			 * Check to see if the transaction was already returned. If this
			 * happens, then it means that new transactions were added while
			 * this class was downloading transaction pages. When a new
			 * transaction is logged, the new transaction "bumps" all other
			 * transactions "down" one. This causes duplicate transactions to be
			 * read.
			 */
			if (!hashesOfReturnedTransactions.add(transaction.hashCode())) {
				continue;
			}

			return transaction;
		}
	}

	/**
	 * Gets the current page number.
	 * @return the page number
	 */
	public int getCurrentPageNumber() {
		return (currentPage == null) ? startAtPage : currentPage.getPage();
	}

	private class ScrapeThread extends Thread {
		private EmcWebsiteConnection connection;

		public ScrapeThread(EmcWebsiteConnection connection) {
			this.connection = connection;
		}

		@Override
		public void run() {
			int pageNumber = 0;
			try {
				while (true) {
					synchronized (RupeeTransactionReader.this) {
						if (cancel) {
							break;
						}
					}

					pageNumber = pageCounter.getAndIncrement();
					if (stopAtPage != null && pageNumber > stopAtPage) {
						break;
					}

					RupeeTransactionPage transactionPage = null;
					try {
						transactionPage = pageSource.getPage(pageNumber, connection);
					} catch (ConnectException e) {
						//one user reported getting connection errors at various points while trying to download 12k pages: http://empireminecraft.com/threads/shop-statistics.22507/page-14#post-684085
						//if there's a connection problem, try re-creating the connection
						logger.log(Level.WARNING, "A connection error occurred while downloading transactions.  Re-creating the connection.", e);
						connection = pageSource.recreateConnection(connection);
						transactionPage = pageSource.getPage(pageNumber, connection);
					} catch (SocketTimeoutException e) {
						logger.log(Level.WARNING, "A connection error occurred while downloading transactions.  Re-creating the connection.", e);
						connection = pageSource.recreateConnection(connection);
						transactionPage = pageSource.getPage(pageNumber, connection);
					}

					//the session *shouldn't* expire while a download is in progress, but run a check in case the sky falls
					if (transactionPage == null) {
						logger.warning("A transaction page couldn't be downloaded due to an invalid session token.  Re-creating the connection.");
						connection = pageSource.createSession();
						transactionPage = pageSource.getPage(pageNumber, connection);
						if (transactionPage == null) {
							throw new InvalidSessionException();
						}
					}

					//EMC will load the first page if an invalid page number is given (in our case, if we're trying to download past the last page)
					boolean lastPageReached = pageNumber > 1 && transactionPage.getFirstTransactionDate().getTime() >= latestTransactionDate.getTime();
					if (lastPageReached) {
						break;
					}

					if (stopAtDate != null && transactionPage.getFirstTransactionDate().getTime() <= stopAtDate.getTime()) {
						/*
						 * If the FIRST transaction in the list comes before the
						 * stop date, then the entire page should be ignored (it
						 * should *not* be added to the queue), and the thread
						 * should terminate (because there are no more
						 * transaction pages to parse).
						 */
						break;
					}

					synchronized (RupeeTransactionReader.this) {
						if (nextPageToPutInQueue == pageNumber) {
							queue.add(transactionPage);

							for (int i = pageNumber + 1; true; i++) {
								RupeeTransactionPage page = buffer.remove(i);
								if (page == null) {
									nextPageToPutInQueue = i;
									break;
								}
								queue.add(page);
							}
						} else {
							buffer.put(pageNumber, transactionPage);
						}
					}

					if (stopAtDate != null && transactionPage.getLastTransactionDate().getTime() <= stopAtDate.getTime()) {
						/*
						 * At this point, we know the FIRST transaction in the
						 * list does *not* come before the stop date (see if
						 * statement above), but the LAST transaction *does*
						 * come before the stop date (this if statement).
						 * 
						 * This means a sub-set of the transactions on this page
						 * come before the stop date, so we still need to add
						 * this page to the queue (as was done above).
						 * 
						 * However, the thread can terminate because we know
						 * there are no more transaction pages to parse.
						 */
						break;
					}
				}
			} catch (Throwable t) {
				synchronized (RupeeTransactionReader.this) {
					if (cancel) {
						return;
					}

					thrown = new IOException("A problem occurred downloading page " + pageNumber + ".", t);
					cancel = true;
				}
			} finally {
				synchronized (RupeeTransactionReader.this) {
					deadThreads++;
					closeQuietly(connection);
					if (deadThreads == threads) {
						//this is the last thread to terminate
						queue.add(noMoreElements);
					}
				}
			}
		}
	}

	@Override
	public synchronized void close() throws IOException {
		cancel = true;
		queue.add(noMoreElements); //null values cannot be added to the queue
	}

	/**
	 * Creates new instances of {@link RupeeTransactionReader}.
	 * @author Michael Angstadt
	 */
	public static class Builder {
		private PageSource pageSource;
		private RupeeTransactionPageScraper pageScraper;
		private Integer startPage = 1, stopPage;
		private Date startDate, stopDate;
		private int threads = 4;

		/**
		 * This constructor is meant for unit testing. The {@link PageSource}
		 * object allows the unit test to directly inject
		 * {@link RupeeTransactionPage} instances into the reader.
		 * @param pageSource produces {@link RupeeTransactionPage} instances
		 */
		Builder(PageSource pageSource) {
			this.pageSource = pageSource;
		}

		/**
		 * @param username the player's username
		 * @param password the player's password
		 */
		public Builder(final String username, final String password) {
			pageSource = new PageSource() {
				@Override
				public RupeeTransactionPage getPage(int pageNumber, EmcWebsiteConnection connection) throws IOException {
					Document document = connection.getRupeeTransactionPage(pageNumber);
					return pageScraper.scrape(document);
				}

				@Override
				public EmcWebsiteConnection recreateConnection(EmcWebsiteConnection connection) throws IOException {
					return new EmcWebsiteConnectionImpl(connection.getCookieStore());
				}

				@Override
				public EmcWebsiteConnection createSession() throws IOException {
					return new EmcWebsiteConnectionImpl(username, password);
				}
			};
		}

		/**
		 * Sets the object used to scrape the rupee transaction pages. Only call
		 * this if you are using custom scribe classes.
		 * @param pageScraper the page scraper
		 * @return this
		 */
		public Builder pageScraper(RupeeTransactionPageScraper pageScraper) {
			this.pageScraper = pageScraper;
			return this;
		}

		/**
		 * Sets the page to start parsing at.
		 * @param page the page
		 * @return this
		 */
		public Builder start(Integer page) {
			startPage = page;
			startDate = null;
			return this;
		}

		public Integer startPage() {
			return startPage;
		}

		/**
		 * Sets the transaction date to start parsing at.
		 * @param date the date
		 * @return this
		 */
		public Builder start(Date date) {
			startDate = date;
			startPage = null;
			return this;
		}

		public Date startDate() {
			return startDate;
		}

		/**
		 * Sets the page to stop parsing at (inclusive).
		 * @param page the page
		 * @return this
		 */
		public Builder stop(Integer page) {
			stopPage = page;
			stopDate = null;
			return this;
		}

		public Integer stopPage() {
			return stopPage;
		}

		/**
		 * Sets the transaction date to stop parsing at (exclusive).
		 * @param date the date
		 * @return this
		 */
		public Builder stop(Date date) {
			stopDate = date;
			stopPage = null;
			return this;
		}

		public Date stopDate() {
			return stopDate;
		}

		/**
		 * Sets the number of background threads to use for downloading rupee
		 * transaction pages from the website. Having multiple threads
		 * significantly improves the speed of the reader, due to the inherent
		 * network latency involved when downloading data from the Internet.
		 * @param threads the number of threads (defaults to 4)
		 * @return this
		 */
		public Builder threads(int threads) {
			this.threads = threads;
			return this;
		}

		/**
		 * Constructs the {@link RupeeTransactionReader} object.
		 * @return the object
		 * @throws IOException
		 */
		public RupeeTransactionReader build() throws IOException {
			if (threads <= 0) {
				threads = 1;
			}

			if (pageScraper == null) {
				pageScraper = new RupeeTransactionPageScraper();
			}

			if (stopPage != null && stopPage < 1) {
				stopPage = 1;
			}

			return new RupeeTransactionReader(this);
		}
	}

	/**
	 * Produces {@link RupeeTransactionPage} objects. Meant to be used for unit
	 * testing.
	 * @author Michael Angstadt
	 */
	interface PageSource {
		RupeeTransactionPage getPage(int pageNumber, EmcWebsiteConnection connection) throws IOException;

		EmcWebsiteConnection recreateConnection(EmcWebsiteConnection connection) throws IOException;

		EmcWebsiteConnection createSession() throws IOException;
	}
}
