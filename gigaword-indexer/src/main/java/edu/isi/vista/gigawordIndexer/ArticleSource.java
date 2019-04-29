package edu.isi.vista.gigawordIndexer;

/**
 * Something which can provide documents for indexing.
 */
public interface ArticleSource extends Iterable<Article>, AutoCloseable {

}
