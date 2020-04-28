import re
import os
import json
import shutil
import tempfile
import pathlib
import sys
from vistautils.parameters import Parameters
from vistautils.parameters_only_entrypoint import parameters_only_entry_point
from typing import AbstractSet, Any, Dict, List, MutableMapping, Optional, Tuple, Type
from immutablecollections import ImmutableSet

# TODO: make paths pathlib.Path objects for better support (Windows/Unix Paths) or/and set up
#  a Parameters file


def cleanup_source_document(sgm_path: str) -> None:
    """Removes CRLF EOLs and in-text markup  in the file to preserve string offsets used for
       annotation (as the ACE annotation offsets use '\n' EOLs and ignore markup)

    Args:
        sgm_path (str): String path of the .sgm file

    One of sgm_string or sgm_path must be specified

    Returns:
        None

    """
    with open(sgm_path, 'r') as sgm_file:
        sgm_string = sgm_file.read()

    # Replace CRLF EOLs with LF
    sgm_string = re.sub(r'<.*?>',  '', sgm_string.replace("\r\n", "\n"))

    with open(sgm_path, 'w') as sgm_file:
        sgm_file.write(sgm_string)


class ApfIngesterInput:
    """Stores necessary input to ingest a document

    Args:
        apf_path (str, Optional): File Path of the .apf document
        apf_string (str, Optional) : String containing the contents of the .apf document

    One of apf_string or apf_path must be specified
    """

    def __init__(self,
                 apf_path: Optional[str] = None,
                 apf_string: Optional[str] = None) -> \
            None:
        if apf_path and apf_string:
            raise ValueError("Only one of apf path and apf string may be specified")
        if not apf_path and not apf_string:
            raise ValueError("One of apf_path or apf_string must be specified")

        if apf_string:
            self.apf_string = apf_string
        else:
            with open(apf_path, 'r') as apf_file:
                self.apf_string = apf_file.read()

    def extract_event_list_to_dict(self,
                                   current_project_dict: Dict[str, List[str]]) -> Dict[str,
                                                                                       List[str]]:
        """Extracts events from the apf file, adding it to the current_project_dict. Utilizes a
        cache to prevent duplicates

        Args:
            current_project_dict (Dict[str, List[str]]): Existing project dict containing other
            Event to Document mappings.

        Returns:
            A Dictionary mapping EVENT_TYPE.EVENT_SUBTYPE to a List of DOC-IDs

        """
        # Cache to prevent duplicate entries (in case there are multiple of the same event in a
        # document)
        visited_keys = []
        for event in re.findall(r"<event ID=.*?>", self.apf_string):
            matcher = re.fullmatch(r'<event ID="(.*?)-EV(.*?)" TYPE="(.*?)" SUBTYPE="(.*?)".*?>',
                                   event)
            if matcher is not None:
                key_name: str = matcher.group(3) + "." + matcher.group(4)
                doc_id: str = matcher.group(1)
                if key_name in visited_keys:
                    continue
                if key_name in current_project_dict:
                    current_project_dict.get(key_name).append(doc_id)
                else:
                    current_project_dict[key_name] = [doc_id]
                visited_keys.append(key_name)
        return current_project_dict


def get_complete_project_to_doc_mapping(ace_corpus_path: str) -> Dict[str, List[str]]:
    """

    Args:
        ace_corpus_path: Path to the corpus directory containing the .sgm and .apf files

    Returns:
         Dict[str, List[str]]: Dict mapping EVENT_TYPE.EVENT_SUBTYPE to a list of document
    names that contain a matching event

    """
    complete_project_map: Dict[str, List[str]] = {}
    for filename in os.listdir(ace_corpus_path):
        if filename.endswith(".sgm"):
            filename_without_extension = filename.split(".sgm")[0]
            source_path = ace_corpus_path + filename
            apf_path = ace_corpus_path + filename_without_extension + ".apf.xml"
            ingester_input = ApfIngesterInput(apf_path)
            ingester_input.extract_event_list_to_dict(complete_project_map)
    # Add empty entry for 'None' type, to generate an empty project
    complete_project_map["None"] = []
    return complete_project_map


def configure_and_generate_project(json_template_path: str,
                                   event_name: str,
                                   user_name: str,
                                   event_doc_map: Dict[str, List[str]],
                                   cached_ser_path: str,
                                   cached_xmi_path: str,
                                   output_dir_path: str) -> None:
    """

    Args:
        json_template_path (str): Path to the basic Inception config template (with custom layers
        already configured)
        event_name (str): Event name in the format of "EVENT_TYPE.EVENT_SUBTYPE"
        user_name (str): The Inception username of the annotator
        event_doc_map (Dict[str, List[str]]): Mapping of event_name to List of document_names ()
        cached_ser_path (str): Path to cached pre-annotated .ser files
        cached_xmi_path (str): Path to cached xmi files
        output_dir_path (str): Output directory

    Returns:
         None

    """
    with open(json_template_path, 'r') as file:
        data = json.load(file)

    # Fill in the project name into the template
    project_name = event_name + '-' + user_name
    data['name'] = project_name
    for layer in data['layers']:
        layer['project_name'] = project_name
        for feature in layer['features']:
            feature['project_name'] = project_name

    # Set user permissions
    data['project_permissions'].append({"level": "USER",
                                        "user": user_name})

    # Use a TemporaryDirectory to create files that need to be zipped
    with tempfile.TemporaryDirectory(dir=output_dir_path) as temp_directory_name:
        # Add annotation .ser files into the annotation_ser directory
        annotation_target_path = pathlib.Path(temp_directory_name) / "annotation_ser"
        os.mkdir(annotation_target_path)
        # Add Source Document information to the config file
        # and put copies of each source file into a new directory called source
        source_target_path = pathlib.Path(temp_directory_name) / "source"
        os.mkdir(source_target_path)
        for doc_name in event_doc_map[event_name]:
            xmi_doc_name = doc_name + '-' + event_name + '.xmi'
            data['source_documents'].append({"name": xmi_doc_name,
                                             "format": "xmi",
                                             "state": "NEW",
                                             "timestamp": "null",
                                             "sentence_accessed": "0",
                                             "created": "null",
                                             "updated": "null"})

            # Copy .ser files in their enclosed directories to annotation_ser to be compressed
            # Directory name ends with a .xmi from the inception export of .xmi documents
            xmi_name = xmi_doc_name
            shutil.copytree(cached_ser_path + xmi_name,
                            annotation_target_path / xmi_name)
            # Copy source files to source directory to be compressed
            # Inception does recognize source files with the .sgm file extension present,
            # so no extension is added
            shutil.copyfile(cached_xmi_path + xmi_name,
                            source_target_path / xmi_name)

            # Cleanup source file (EOLs and in-text markup)
            # cleanup_source_document(source_target_path / doc_name)

        # The 'exportedproject' prefix is necessary because the inception importer runs a check
        # specifically for this prefix and the presence of the .json extension
        config_filename = 'exportedproject' + project_name + '.json'

        # Using a Path object to allow windows & Unix file paths
        new_config_file_path = pathlib.Path(temp_directory_name) / config_filename
        with open(new_config_file_path, 'w') as project_config:
            json.dump(data, project_config, indent=4)  # indents to for human-readability

        # Compress the contents of the temporaryDirectory to a .zip file
        shutil.make_archive(pathlib.Path(output_dir_path) / project_name,
                            'zip',
                            root_dir=temp_directory_name)


def flatten_ace_data(corpus_paths: List[str], destination_path: str):
    """flatten ace data files (apf and smg only) into a single directory from a list of directory"""
    for corpus_path in corpus_paths:
        for filename in os.listdir(corpus_path):
            if filename.endswith(".sgm") or filename.endswith(".apf.xml"):
                shutil.copyfile(corpus_path + filename, destination_path + filename)


def main(params: Parameters):
    # List of the six ACE corpus /adj/ folders (one for each type: bc, bn, cts, nw, un, wl)
    corpus_paths = params.arbitrary_list("corpus_paths")
    # Path to the project config file template (json file)
    json_template_path = params.string("json_template_path")
    # Path to the cached_annotation_ser directory
    annotation_ser_path = params.string("annotation_ser_path")
    # Path to the cached_xmi directory
    cached_xmi_path = params.string("cached_xmi_path")
    # Path to target corpus (narrowed ACE-Corpus)
    ace_data_path = params.string("ace_data_path")

    # List of users (strings)
    user_list = params.arbitrary_list("user_list")
    # List of event type (Format: "EVENT_TYPE.SUBTYPE" strings)
    event_list = params.arbitrary_list("event_list")

    # Output Directory Path where configured projects are moved to (use an empty directory)
    output_dir_path = params.string("output_dir_path")

    flatten_ace_data(corpus_paths, ace_data_path)

    complete_map = get_complete_project_to_doc_mapping(ace_data_path)

    for user in user_list:
        for event_type in event_list:
            configure_and_generate_project(json_template_path,
                                           event_type,
                                           user,
                                           complete_map,
                                           annotation_ser_path,
                                           cached_xmi_path,
                                           output_dir_path)


if __name__ == "__main__":
    parameters_only_entry_point(main)
