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
Note: If this command fails in building just the annotation-utils module, skip it in the build as we do not need it to index corpora with:
```
mvn clean install -pl !annotation-utils
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

### Backing up ElasticSearch Indices
Reference: https://www.elastic.co/guide/en/elasticsearch/reference/6.6/modules-snapshots.html

In the case where our indices get deleted for whatever reason, it is important
to have backups ready to restore them.
Here are the steps for creating and restoring backups.

#### Creating backups

If the desired snapshot repository has already been created and registered,
skip to step 4.

1. Determine where the backups will be saved and create the directory if
it doesn't already exist.
Then add this line to `elasticsearch.yml` in the `Paths` section like so:
(note that you will need to be logged in as `elasticsearch`)

    ```
   path.repo: ["/nas/gaia/elasticsearch_data"]
   ```

2. Restart ElasticSearch.

    In order to register the backup repo path, ElasticSearch must be restarted.
    If ElasticSearch hasn't already been stopped, do steps i and ii below.

    1. Find the PID with `jps | grep Elasticsearch`.
    2. Run `kill -SIGKILL [PID]`. You can then check the logs to confirm that
    the service successfully shut down.
    3. Restart the service: `cd /lfs1/ElasticSearch; bash ./elasticsearch-6.6.1/bin/elasticsearch`

3. Create the backup repository - this is where "snapshots" will be saved.
   ```
   curl -X PUT "http://localhost:9200/_snapshot/elasticsearch_backup?pretty" -H 'Content-Type: application/
   json' -d
   '{
        "type": "fs",
        "settings": {
            "location": "/nas/gaia/elasticsearch_data"
        }
   }'
   ```
   If you are getting an `access_denied_exception`, make sure that the user
   `elasticsearch` has permission to write to your chosen directory, then try again.
   
   Verify that the repository was registered.
   ```
   curl -X POST "http://localhost:9200/_snapshot/elasticsearch_backup/_verify"
   ```
   
   You can check the repository information using this command:
   ```
   curl -X GET "http://localhost:9200/_snapshot/elasticsearch_backup"
   ```

4. Create a snapshot of all indices in the cluster and save it to the repository.
   ```
   # PUT /_snapshot/elasticsearch_backup/<snapshot-{current-date}>
   curl -X PUT "http://localhost:9200/_snapshot/elasticsearch_backup/%3Csnapshot-%7Bnow%2Fd%7D%3E?wait_for_completion=true"
   ```
   If `wait_for_completion` is set to `true`, the request will not return until the snapshot
   is completed. The default is to return immediately after initialization.
   
   You can view the status of a snapshot with a `GET` command:
   ```
   curl -X GET "http://localhost:9200/_snapshot/elasticsearch_backup/<snapshot_name>/_status"
   ```
   
   Information from a completed snapshot can be obtained with a similar command:
   ```
   curl -X GET "http://localhost:9200/_snapshot/elasticsearch_backup/<snapshot_name>"
   ```
 
#### Restoring backups  

To restore a backup:
   ```
    curl -X POST "http://localhost:9200/_snapshot/elasticsearch_backup/<snapshot_name>/_restore"
   ```

### Checking ElasticSearch logs

```
less /lfs1/ElasticSearch/elasticsearch-6.6.1/logs/elasticsearch.log
```

### Indexing multiple languages

* Be sure to use different `indexName` parameters for each language.
