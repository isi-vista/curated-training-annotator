from cassis import *
from cassis.xmi import *
import re
import codecs
import os
import time
import copy
from vistautils.parameters import Parameters
from vistautils.parameters_only_entrypoint import parameters_only_entry_point
from typing import AbstractSet, Any, Dict, List, MutableMapping, Optional, Tuple, Type
from pathlib import Path


def create_cas_from_apf(*, apf_filename: str, apf_path: str, source_sgm_path: str,
                        output_dir_path: Path, typesystem, cas_template):
    """Create cas from apf file then converts and deserializes the apf to an xmi file."""
    # Do not edit template directly, make a copy
    cas = copy.deepcopy(cas_template)
    # set mime type
    cas.sofa_mime = "text"

    # set source text
    with open(source_sgm_path, 'r', encoding='utf-8') as source_file:
        cas.sofa_string = process_string_and_remove_tags(source_file.read())

    """ This is unnecessary as Inception auto-generates tokens (this also causes errors)
    # Add all word offsets from the source sgm file
    Token = typesystem.get_type('de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token')
    for word, begin, end in tokenize_words(source_sgm_path):
        # end is end+1 due to inception is not end-inclusive
        token = Token(begin=begin, end=end + 1, order='0')
        cas.add_annotation(token)
    """

    """
    # Code to import entity mentions and its entity_type
    Custom_Entity = typesystem.get_type('webanno.custom.AceEntitySpan')
    apf_entity_list = get_apf_entities_to_list(apf_path)
    for entity_id, entity_type, start_offset, end_offset in apf_entity_list:
        entity_annotation = Custom_Entity(begin=start_offset, end=end_offset,
                                          entity_type=entity_type)
        cas.add_annotation(entity_annotation)
    """
    Custom_Event = typesystem.get_type('webanno.custom.CTEventSpan')
    Custom_Event_Relation = typesystem.get_type('webanno.custom.CTEventSpanType')
    apf_event_list = get_apf_events_to_list(apf_path)
    # Key: List[(mention_start_offset, mention_end_offset, arguments_list[(role, start, end)])]
    merged_event_dict: Dict[str, List[(int, int, List[(str, int, int)])]] = {}

    # combine lists with the same event type to a list of tuples (start_offset, end_offset)
    for event_id, event_type, start_offset, end_offset, arg_list in apf_event_list:
        if event_type in merged_event_dict:
            merged_event_dict[event_type].append((start_offset, end_offset, arg_list))
        else:
            merged_event_dict[event_type] = [(start_offset, end_offset, arg_list)]

    for event_type_entry, offset_list in merged_event_dict.items():
        temp_cas = copy.deepcopy(cas)
        for (start, end, arg_list) in offset_list:
            event_annotation = Custom_Event(begin=start, end=end+1,
                                            negative_example="false")
            temp_cas.add_annotation(event_annotation)
            for arg_role, arg_start, arg_end in arg_list:
                arg_annotation = Custom_Event(begin=arg_start, end=arg_end+1,
                                              negative_example="false")
                temp_cas.add_annotation(arg_annotation)
                arg_relation = Custom_Event_Relation(relation_type=arg_role,
                                                     begin=arg_start,
                                                     end=arg_end+1,
                                                     Governor=arg_annotation,
                                                     Dependent=event_annotation)
                temp_cas.add_annotation(arg_relation)

        # Serialize the final cas to xmi to the output directory with the same filename as the apf
        output_file_name = apf_filename.replace(".apf.xml", "-" + event_type_entry + ".xmi")
        serialize_cas_to_xmi(output_dir_path / output_file_name, temp_cas)


def serialize_cas_to_xmi(output_path: Path, cas_to_serialize):
    cas_serializer = CasXmiSerializer()
    cas_serializer.serialize(str(output_path), cas=cas_to_serialize)


# Deprecated
def tokenize_words(source_string_path: str):
    with codecs.open(source_string_path, mode='r', encoding='utf-8') as a_source_file:
        source_file_text = a_source_file.read()
        processed_text = process_string_and_remove_tags(source_file_text)

    # returns a list of (word, start_offset, end_offset) tuples for every word in the source
    return [(m.group(0), m.start(), m.end() - 1) for m in re.finditer(r'\S+', processed_text)]


def process_string_and_remove_tags(raw_string: str):
    """Removes tags and converts newlines to match ace offsets alignment"""
    raw_string = raw_string.replace('\r\n', '\n')
    return re.sub(r'<[\s\S]*?>', '', raw_string)


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
            start_offset = int(mention_match_obj.group(2))
            end_offset = int(mention_match_obj.group(3))
            ret.append((entity_id, entity_type, start_offset, end_offset))
    return ret


def get_apf_events_to_list(apf_path: str):
    """Converts events from apf file to a list of
    (event_ID, event_type, start_offset, end_offset, argument_list) tuples"""
    ret = []
    with open(apf_path, 'r') as apf_file:
        apf_string = apf_file.read()
    apf_string = apf_string.replace('\n', '')
    event_data_list = re.findall(r'<event ID=.*?</event>', apf_string)
    for event_data in event_data_list:
        match_obj = re.match(r'<event ID="(.*?)" TYPE="(.*?)" SUBTYPE="(.*?)"(.*?)">',
                             event_data)
        event_id = match_obj.group(1)
        event_type = match_obj.group(2) + '.' + match_obj.group(3)
        event_mention_list = re.findall(r'<event_mention ID=.*?</event_mention>', event_data)
        for event_mention in event_mention_list:
            mention_match_obj = re.match(r'<event_mention ID="(.*?)".*?<anchor>.*?<charseq '
                                         r'START="(.*?)" END="(.*?)">',
                                         event_mention)
            # mention id is unused for now
            # mention_id = mention_match_obj.group(1)
            start_offset = int(mention_match_obj.group(2))
            end_offset = int(mention_match_obj.group(3))
            event_argument_list = re.findall(
                r'<event_mention_argument.*?</event_mention_argument>', event_mention)
            argument_list = []
            for argument_data in event_argument_list:
                argument_match_obj = re.match(r'<event_mention_argument.*?ROLE="(.*?)">.*?<charseq '
                                              r'START="(.*?)" END="(.*?)">',
                                              argument_data)
                # Tuples of (argument_role, arg_start, arg_end)
                argument_list.append((argument_match_obj.group(1),
                                      int(argument_match_obj.group(2)),
                                      int(argument_match_obj.group(3))))

            # Tuple of (event_id, event_type, start_offset, end_offset, argument_list)
            # for each mention
            ret.append((event_id, event_type, start_offset, end_offset, argument_list))
    return ret


def main(params: Parameters):
    # create_cas_from_apf(TEST_APF_PATH, TEST_SGM_PATH, OUTPUT_DIR_PATH)
    corpus_paths = params.arbitrary_list("corpus_paths")
    output_xmi_dir_path = params.creatable_directory("cached_xmi_path")
    type_system_path = params.existing_file("type_system_path")
    cas_xmi_template_path = params.existing_file("cas_xmi_template_path")

    # Load Typesystem
    with type_system_path.open('rb') as file:
        typesystem = load_typesystem(file)

    # Load xmi_template
    with cas_xmi_template_path.open('rb') as cas_xmi_file:
        cas_template = load_cas_from_xmi(cas_xmi_file, typesystem=typesystem)

    for ace_corpus_path in corpus_paths:
        print('Processing apf files from: ' + ace_corpus_path)
        start_time = time.perf_counter()
        for filename in os.listdir(ace_corpus_path):
            if filename.endswith(".apf.xml"):
                print("Processing " + filename)
                create_cas_from_apf(apf_filename=filename,
                                    apf_path=ace_corpus_path + filename,
                                    source_sgm_path=ace_corpus_path + filename.replace(
                                        ".apf.xml", ".sgm"),
                                    output_dir_path=output_xmi_dir_path, typesystem=typesystem,
                                    cas_template=cas_template)
        elapsed_time = time.perf_counter() - start_time
        print(f"Processing Completed. Time elapsed: {elapsed_time:0.4f} seconds")


if __name__ == "__main__":
    parameters_only_entry_point(main)
