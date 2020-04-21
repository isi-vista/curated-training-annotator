from cassis import *
from cassis.xmi import *
import re
import codecs
import os
import time
from typing import AbstractSet, Any, Dict, List, MutableMapping, Optional, Tuple, Type

CORPUS_PATHS = ["C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\bc\\adj\\",
                "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\bn\\adj\\",
                "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\cts\\adj\\",
                "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\nw\\adj\\",
                "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\un\\adj\\",
                "C:\\isi\\curated-training-annotator\\ace_2005_td_v7\\data\\English\\wl\\adj\\"
                ]
TEST_APF_PATH = "C:\\isi\\apf_ingester\\test_apf\\test.apf.xml"
TEST_SGM_PATH = "C:\\isi\\apf_ingester\\test_apf\\test.sgm"
OUTPUT_DIR_PATH = "apf_ingester_output\\xmi\\"


def create_cas_from_apf(apf_filename: str, apf_path: str, source_sgm_path: str,
                        output_dir_path: str):
    """Create cas from apf file then converts and deserializes the apf to an xmi file."""

    # Load Typesystem
    with open('TypeSystem.xml', 'rb') as file:
        typesystem = load_typesystem(file)

    # Load xmi_template
    with open('cas_xmi_template.xmi', 'rb') as cas_xmi_file:
        cas = load_cas_from_xmi(cas_xmi_file, typesystem=typesystem)

    # set mime type
    cas.sofa_mime = "text"

    # set source text
    with open(source_sgm_path, 'r', encoding='utf-8') as source_file:
        cas.sofa_string = process_string_and_remove_tags(source_file.read())

    # Add all word offsets from the source sgm file
    Token = typesystem.get_type('de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token')
    for word, begin, end in tokenize_words(source_sgm_path):
        # end is end+1 due to inception's end offset counting method
        token = Token(begin=begin, end=end + 1, order='0')
        cas.add_annotation(token)

    apf_entity_list = get_apf_entities_to_list(apf_path)
    Custom_Entity = typesystem.get_type('webanno.custom.AceEntitySpan')
    for entity_id, entity_type, start_offset, end_offset in apf_entity_list:
        entity_annotation = Custom_Entity(begin=start_offset, end=end_offset,
                                          entity_type=entity_type)
        cas.add_annotation(entity_annotation)

    # Serialize the final cas to xmi to the output directory with the same filename as the apf
    output_file_name = apf_filename.replace(".apf.xml", ".xmi")
    serialize_cas_to_xmi(output_dir_path + output_file_name, cas)


def serialize_cas_to_xmi(output_path: str, cas_to_serialize):
    cas_serializer = CasXmiSerializer()
    cas_serializer.serialize(output_path, cas=cas_to_serialize)


def tokenize_words(source_string_path: str):
    with codecs.open(source_string_path, mode='r', encoding='utf-8') as a_source_file:
        source_file_text = a_source_file.read()
        processed_text = process_string_and_remove_tags(source_file_text)

    # returns a list of (word, start_offset, end_offset) tuples for every word in the source
    return [(m.group(0), m.start(), m.end() - 1) for m in re.finditer(r'\S+', processed_text)]


def process_string_and_remove_tags(raw_string: str):
    """Removes tags and converts newlines to match ace offsets alignment"""
    raw_string = raw_string.replace('\r\n', '\n')
    return re.sub(r'<(.*?)>', '', raw_string)


def get_apf_entities_to_list(apf_path: str):
    """Converts entities from apf file to a list of
    (entity_ID, entity_type, start_offset, end_offset) tuples"""
    ret = []
    with open(apf_path, 'r') as apf_file:
        apf_string = apf_file.read()
    apf_string = apf_string.replace('\n', '')
    entity_data_list = re.findall(r'<entity ID=.*?</entity>', apf_string)
    for entity_data in entity_data_list:
        match_obj = re.match(r'<entity ID="(.*?)" TYPE="(.*?)" SUBTYPE="(.*?)" CLASS="(.*?)">',
                             entity_data)
        entity_id = match_obj.group(1)
        entity_type = match_obj.group(2) + '.' + match_obj.group(3)
        entity_mention_list = re.findall(r'<entity_mention ID=.*?</entity_mention>', entity_data)
        for entity_mention in entity_mention_list:
            mention_match_obj = re.match(r'<entity_mention ID="(.*?)".*?<extent>.*?<charseq '
                                         r'START="(.*?)" END="(.*?)">',
                                         entity_mention)
            # mention id is unused for now
            # mention_id = mention_match_obj.group(1)
            start_offset = mention_match_obj.group(2)
            end_offset = mention_match_obj.group(3)
            ret.append((entity_id, entity_type, start_offset, end_offset))
    return ret


def main():
    # create_cas_from_apf(TEST_APF_PATH, TEST_SGM_PATH, OUTPUT_DIR_PATH)

    for ace_corpus_path in CORPUS_PATHS:
        print('Processing apf files from: ' + ace_corpus_path)
        start_time = time.perf_counter()
        for filename in os.listdir(ace_corpus_path):
            if filename.endswith(".apf.xml"):
                print("Processing " + filename)
                create_cas_from_apf(filename,
                                    ace_corpus_path + filename,
                                    ace_corpus_path + filename.replace(".apf.xml", ".sgm"),
                                    OUTPUT_DIR_PATH)
        elapsed_time = time.perf_counter() - start_time
        print(f"Processing Completed. Time elapsed: {elapsed_time:0.4f} seconds")


if __name__ == "__main__":
    main()
