### Upload Dataset for index
#### Gigaword Corpus:
1. Upload English Gigaword V5 (catalog_id: LDC2011T07) to `/lfs1/eng.gigaword.v5/`
2. Unpack the dataset:
```
cd /lfs1/eng.gigaword.v5/
tar xvzf gigaword_eng_5_LDC2011T07.tgz
```
#### ACE Corpus:
1. Upload ACE_Corpus (2005) (catalog_id: LDC2006T06) to `/lfs1/ace.2005.v7/`
2. Unpack the dataset:
```
cd /lfs1/ace.2005.v7/
tar xvzf ace_2005_td_v7_LDC2006T06.tgz
```

### Install ElasticSearch and start the service
1. Upload package of Elasticsearch to `/lfs1/ElasticSearch/`
    ```
    cd /lfs1/ElasticSearch/
    wget https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-6.6.1.tar.gz
    ```

2. Unpack the software and start the service:
    ```
    tar xvzf elasticsearch-6.6.1.tar.gz
    ```

    On CentOS, the following content needs to be in the file `/etc/sysctl.d/90-elastic-search.conf`:
    ```
    vm.max_map_count=262144
    ```

3. Start the service:
    ```
    bash ./elasticsearch-6.6.1/bin/elasticsearch
    ```

4. Check service status:
    ``` 
    curl -X GET http://localhost:9200
    ```
   
 It will show the elastic version and other information of the installation.

### Set up Maven
This is so we can build `gigaword-indexer` and we will install Maven manually:
```
wget http://apache.mirrors.lucidnetworks.net/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz
tar xzvf apache-maven-3.6.3-bin.tar.gz
```

Then add `apache-maven-3.6.3/bin` to your `PATH`

### Install gigaword-indexer:
```
git clone https://github.com/isi-vista/curated-training-annotator.git
cd curated-training-annotator
mvn clean install
```

### English Gigaword V5 Indexing
In the build of `gigaword-indexer`, create a parameter file `index_gigaword.english.params` which has following parameters:

#### Gigaword Corpus:
```
indexName: gigaword
gigawordDirectoryPath: /lfs1/eng.gigaword.v5/gigaword_eng_5
```
#### ACE Corpus:
```
indexName: ace
format: ace
lang: english
corpusDirectoryPath: /lfs1/ace_2005_td_v7
```

Then, run:
```
gigaword-indexer/target/appassembler/bin/indexGigaword index_gigaword.english.params
```

It will take a while to complete indexing for entire dataset. When indexing is done, a simple test of the search engine can be done with
1. Check the index of database:
    ```
    curl -XGET 'http://localhost:9200/_cat/indices?v'
    ```
    
    Following outputs are expected:
    ```
    health status index    uuid                   pri rep docs.count docs.deleted store.size pri.store.size
    yellow open   gigaword E4YrepZPR8qydtOl7wjaIQ   5   1    9875524            0     26.6gb         26.6gb
    ```

2. A simple query:
    ```
    curl -XGET http://localhost:9200/_search?q=USA
    ```
A JSON file that reports the search results (including seach time, hit number and documents that contain the keywords) is expected.

### Checking ElasticSearch logs

```
less /lfs1/ElasticSearch/elasticsearch-6.6.1/logs/elasticsearch.log
```

### Indexing multiple languages

* Be sure to use different `indexName` parameters for each language.
