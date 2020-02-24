package edu.isi.vista.aceIndexer;

/**
 * Something which can provide documents for indexing.
 */
public interface ArticleSource extends Iterable<Article>, AutoCloseable {

}
